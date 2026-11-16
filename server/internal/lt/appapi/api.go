// Package appapi 是一起听手机 App 用的接口（/api/v1）和管理员的邀请码、成员管理接口。
package appapi

import (
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/guohuiyuan/music-lib/model"
	qrcode "github.com/skip2/go-qrcode"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/go-music-dl/internal/lt/hub"
	"github.com/guohuiyuan/go-music-dl/internal/lt/identity"
	"github.com/guohuiyuan/go-music-dl/internal/lt/members"
	"github.com/guohuiyuan/go-music-dl/internal/lt/playurl"
	"github.com/guohuiyuan/go-music-dl/internal/lt/podcast"
	"github.com/guohuiyuan/go-music-dl/internal/lt/room"
	"github.com/guohuiyuan/go-music-dl/internal/lt/videourl"
)

const (
	Prefix    = "/api/v1"
	DBFile    = "data/listen_together.db"
	AvatarDir = "data/avatars"
	// KeyFile 是服务器身份私钥。丢了或换了，所有人都要重新加入；搬家时连同 data/ 一起拷走。
	KeyFile   = "data/lt_server_key.pem"
	avatarMax = 512 << 10
	// Leave room for a 10,000-track public playlist and concurrent room searches.
	songCacheMax = 20000
	memberKey    = "lt_member"
)

// Sources 是 App 能搜索的平台，顺序即默认优先级。netease_voice 搜的是网易云播客里的单条声音；
// bilibili 不能按关键词搜，要粘贴视频链接或 BV 号，只播音轨。
var Sources = []string{"netease", "qq", "kugou", "kuwo", "bilibili", VoiceSource}

const (
	VoiceSource  = "netease_voice"
	searchLimit  = 30
	programLimit = 50
)

type API struct {
	Members   *members.Store
	Resolver  *playurl.Resolver
	Videos    *videourl.Resolver
	Rooms     *room.Manager
	Hub       *hub.Hub
	Search    func(source, keyword string) ([]model.Song, error)
	Playlists func(source, link string) (*model.Playlist, []model.Song, error)
	Podcasts  *podcast.Client
	YouTube   *youtubeClient
	songs     *songCache
	avatarDir string
	// Identity 为 nil 时 /info 返回 404（测试里不需要时可以不设）。
	Identity *identity.Identity
	video    *videoEnv
}

func New(store *members.Store) *API {
	a := &API{
		Members:   store,
		Resolver:  playurl.New(),
		Videos:    videourl.New(),
		Hub:       hub.New(),
		songs:     newSongCache(songCacheMax),
		Playlists: publicPlaylist,
		avatarDir: AvatarDir,
		Podcasts:  podcast.New(func() string { return core.CM.Get("netease") }),
		YouTube:   newYouTubeClient(),
	}
	a.Search = func(source, keyword string) ([]model.Song, error) {
		if source == VoiceSource {
			return a.Podcasts.SearchVoices(keyword, searchLimit)
		}
		if source == "bilibili" {
			return lookupBilibili(keyword)
		}
		if source == YouTubeSource {
			return a.YouTube.lookup(keyword)
		}
		fn := core.GetSearchFunc(source)
		if fn == nil {
			return nil, fmt.Errorf("不支持的平台 %s", source)
		}
		return fn(keyword)
	}
	if mode := playurl.Mode(os.Getenv("LT_PLAY_MODE")); mode != "" {
		a.Resolver.Mode = mode
	}
	a.Rooms = room.NewManager(room.RealClock(), a.Hub, a.warm)
	a.loadVideoEnv()
	a.Hub.Rooms = a.Rooms
	a.Hub.Authenticate = store.Authenticate
	a.Hub.AddSong = a.addSong
	return a
}

// Register 是挂到 go-music-dl web 服务上的入口：App 接口在根路径 /api/v1，
// 管理接口挂在原有需要登录的 configAPI 下（/music/lt/...）。
func Register(r *gin.Engine, configAPI *gin.RouterGroup) {
	if err := os.MkdirAll(filepath.Dir(DBFile), 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "listen-together: %v\n", err)
		return
	}
	store, err := members.Open(DBFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "listen-together: open members db: %v\n", err)
		return
	}
	id, err := identity.Load(KeyFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "listen-together: 服务器身份密钥: %v\n", err)
		return
	}
	a := New(store)
	a.Identity = id
	a.Mount(r.Group(Prefix))
	a.MountAdmin(configAPI.Group("/lt"))
	fmt.Printf("Listen-together API at %s (play mode: %s, server id: %s)\n", Prefix, a.Resolver.Mode, id.ID())
}

func (a *API) Mount(g *gin.RouterGroup) {
	g.POST("/join", a.handleJoin)
	g.GET("/info", a.handleInfo)
	g.GET("/ws", func(c *gin.Context) { a.Hub.ServeHTTP(wsWriter{c.Writer}, c.Request) })

	auth := g.Group("", a.authRequired)
	auth.GET("/me", func(c *gin.Context) {
		c.JSON(200, meJSON(c.MustGet(memberKey).(members.Member)))
	})
	auth.PATCH("/me", a.handleUpdateProfile)
	auth.PUT("/me/avatar", a.handleUploadAvatar)
	// 头像图片不需要鉴权：文件名是不可猜的 userId，客户端按 ?v= 版本号长期缓存。
	g.GET("/avatars/:id", a.handleAvatar)
	auth.GET("/rooms", func(c *gin.Context) { c.JSON(200, gin.H{"rooms": a.Rooms.List()}) })
	auth.GET("/search", a.handleSearch)
	auth.GET("/playlists/resolve", a.handlePlaylist)
	auth.GET("/podcasts", a.handlePodcasts)
	auth.GET("/podcasts/:id/programs", a.handlePrograms)
	auth.GET("/play/:source/:id", a.handlePlay)
	auth.POST("/play/:source/:id/direct_failed", func(c *gin.Context) {
		a.Resolver.MarkDirectFailed(c.Param("source"), c.Param("id"))
		c.JSON(200, gin.H{"ok": true})
	})
	auth.GET("/stream/:source/:id", a.handleStream)
	auth.GET("/lyrics/:source/:id", a.handleLyrics)
	a.mountVideo(auth)
}

func (a *API) authRequired(c *gin.Context) {
	token := strings.TrimSpace(strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer "))
	if token == "" {
		// 播放器拉流时不方便带请求头，允许放在查询参数里。
		token = c.Query("token")
	}
	m, err := a.Members.Authenticate(token)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "UNAUTHORIZED"})
		return
	}
	c.Set(memberKey, m)
	c.Next()
}

func (a *API) handleJoin(c *gin.Context) {
	var req struct {
		InviteCode string `json:"inviteCode"`
		Nickname   string `json:"nickname"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": "BAD_REQUEST"})
		return
	}
	m, token, err := a.Members.Join(strings.ToUpper(strings.TrimSpace(req.InviteCode)), req.Nickname)
	switch {
	case errors.Is(err, members.ErrInviteInvalid):
		c.JSON(403, gin.H{"error": "INVITE_INVALID"})
	case errors.Is(err, members.ErrBadNickname):
		c.JSON(400, gin.H{"error": "BAD_NICKNAME"})
	case err != nil:
		c.JSON(500, gin.H{"error": err.Error()})
	default:
		c.JSON(200, gin.H{"token": token, "userId": m.ID, "nickname": m.Nickname})
	}
}

func meJSON(m members.Member) gin.H {
	return gin.H{"userId": m.ID, "nickname": m.Nickname, "avatar": m.Avatar}
}

func (a *API) handleUpdateProfile(c *gin.Context) {
	var req struct {
		Nickname *string `json:"nickname"`
		Avatar   *string `json:"avatar"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": "BAD_REQUEST"})
		return
	}
	// 照片头像只能通过上传设置，这里只接受默认或内置头像。
	if req.Avatar != nil && strings.HasPrefix(*req.Avatar, "photo:") {
		c.JSON(400, gin.H{"error": "BAD_AVATAR"})
		return
	}
	me := c.MustGet(memberKey).(members.Member)
	m, err := a.Members.UpdateProfile(me.ID, req.Nickname, req.Avatar)
	switch {
	case errors.Is(err, members.ErrBadNickname):
		c.JSON(400, gin.H{"error": "BAD_NICKNAME"})
	case errors.Is(err, members.ErrBadAvatar):
		c.JSON(400, gin.H{"error": "BAD_AVATAR"})
	case err != nil:
		c.JSON(500, gin.H{"error": err.Error()})
	default:
		a.Hub.ProfileChanged(m)
		c.JSON(200, meJSON(m))
	}
}

// handleUploadAvatar 接收客户端压缩好的图片（请求体就是图片本身，建议 256×256 JPEG，几十 KB）。
func (a *API) handleUploadAvatar(c *gin.Context) {
	data, err := io.ReadAll(io.LimitReader(c.Request.Body, avatarMax+1))
	if err != nil || len(data) == 0 {
		c.JSON(400, gin.H{"error": "BAD_REQUEST"})
		return
	}
	if len(data) > avatarMax {
		c.JSON(413, gin.H{"error": "AVATAR_TOO_LARGE", "msg": "头像图片不能超过 512KB"})
		return
	}
	switch http.DetectContentType(data) {
	case "image/jpeg", "image/png", "image/webp":
	default:
		c.JSON(400, gin.H{"error": "BAD_AVATAR", "msg": "只支持 JPEG、PNG、WebP"})
		return
	}
	me := c.MustGet(memberKey).(members.Member)
	if err := os.MkdirAll(a.avatarDir, 0o755); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	if err := os.WriteFile(filepath.Join(a.avatarDir, me.ID), data, 0o644); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	avatar := "photo:" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	m, err := a.Members.UpdateProfile(me.ID, nil, &avatar)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	a.Hub.ProfileChanged(m)
	c.JSON(200, meJSON(m))
}

func (a *API) handleAvatar(c *gin.Context) {
	id := c.Param("id")
	if _, err := uuid.Parse(id); err != nil {
		c.Status(404)
		return
	}
	data, err := os.ReadFile(filepath.Join(a.avatarDir, id))
	if err != nil {
		c.Status(404)
		return
	}
	c.Header("Cache-Control", "public, max-age=31536000, immutable")
	c.Data(200, http.DetectContentType(data), data)
}

func (a *API) handleSearch(c *gin.Context) {
	q := strings.TrimSpace(c.Query("q"))
	source := c.DefaultQuery("source", Sources[0])
	if q == "" {
		c.JSON(400, gin.H{"error": "BAD_REQUEST"})
		return
	}
	if looksLikeBilibili(q) {
		// 在别的平台标签里粘了 B 站链接也照样解析，旧版 App 没有 B站 标签也能用。
		source = "bilibili"
	} else if looksLikeYouTube(q) {
		source = YouTubeSource
	}
	songs, err := a.Search(source, q)
	if err != nil {
		c.JSON(502, gin.H{"error": err.Error()})
		return
	}
	tracks := make([]room.TrackRef, 0, len(songs))
	for i := range songs {
		if songs[i].IsInvalid {
			continue
		}
		a.songs.put(songs[i])
		tracks = append(tracks, toTrack(&songs[i]))
	}
	c.JSON(200, gin.H{"tracks": tracks})
}

func (a *API) handlePodcasts(c *gin.Context) {
	q := strings.TrimSpace(c.Query("q"))
	if q == "" {
		c.JSON(400, gin.H{"error": "BAD_REQUEST"})
		return
	}
	list, err := a.Podcasts.SearchPodcasts(q, searchLimit)
	if err != nil {
		c.JSON(502, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"podcasts": list})
}

func (a *API) handlePrograms(c *gin.Context) {
	offset, _ := strconv.Atoi(c.Query("offset"))
	if offset < 0 {
		offset = 0
	}
	songs, total, more, err := a.Podcasts.Programs(c.Param("id"), offset, programLimit)
	if err != nil {
		c.JSON(502, gin.H{"error": err.Error()})
		return
	}
	tracks := make([]room.TrackRef, 0, len(songs))
	for i := range songs {
		a.songs.put(songs[i])
		tracks = append(tracks, toTrack(&songs[i]))
	}
	c.JSON(200, gin.H{"tracks": tracks, "total": total, "hasMore": more, "nextOffset": offset + programLimit})
}

// song 优先取搜索缓存；服务器重启后缓存为空，用 source+id 构造一个最小对象，
// 大部分平台只凭 ID 就能解析，缺时长时试听检测会跳过。
func (a *API) song(source, id string) *model.Song {
	if s, ok := a.songs.get(source, id); ok {
		return s
	}
	return &model.Song{Source: source, ID: id}
}

func (a *API) handlePlay(c *gin.Context) {
	source, id := c.Param("source"), c.Param("id")
	// 音质是每个成员自己选的（App 每次请求带上），只影响这个人拿到的地址。
	res, err := a.Resolver.LookupQuality(a.song(source, id), c.Query("refresh") == "1", playurl.ParseQuality(c.Query("quality")))
	if err != nil {
		code := 502
		if errors.Is(err, playurl.ErrUnplayable) {
			code = 409
		}
		c.JSON(code, gin.H{"error": room.CodeUnplayable, "msg": err.Error()})
		return
	}
	res.Proxy = Prefix + "/stream/" + source + "/" + id
	if res.Quality == playurl.QualityLossless {
		res.Proxy += "?quality=lossless"
	}
	c.JSON(200, res)
}

// handleStream 是直连失败后的回退：服务器带 Cookie 拉 CDN，按 Range 转发给手机。
func (a *API) handleStream(c *gin.Context) {
	song := a.song(c.Param("source"), c.Param("id"))
	url, err := a.Resolver.StreamURL(song, playurl.ParseQuality(c.Query("quality")))
	if err != nil {
		c.String(502, "resolve failed")
		return
	}
	rangeHeader := c.GetHeader("Range")
	if fetch, handled, err := core.NewSourceRangeFetch(url, song.Source, rangeHeader); err != nil {
		c.String(502, "upstream range error")
		return
	} else if handled {
		ext := strings.TrimPrefix(fetch.Ext, ".")
		if ext == "" {
			ext = "mp3"
		}
		c.Header("Content-Type", core.AudioMimeByExt(ext))
		c.Header("Accept-Ranges", "bytes")
		c.Header("Content-Length", strconv.FormatInt(fetch.ContentLength, 10))
		if fetch.ContentRange != "" {
			c.Header("Content-Range", fetch.ContentRange)
		}
		c.Status(fetch.StatusCode)
		_ = fetch.WriteTo(c.Writer)
		return
	}

	req, err := core.BuildSourceRequest("GET", url, song.Source, rangeHeader)
	if err != nil {
		c.String(502, "upstream request error")
		return
	}
	resp, err := http.DefaultClient.Do(req.WithContext(c.Request.Context()))
	if err != nil {
		c.String(502, "upstream stream error")
		return
	}
	defer resp.Body.Close()
	for _, h := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := resp.Header.Get(h); v != "" {
			c.Header(h, v)
		}
	}
	c.Status(resp.StatusCode)
	_, _ = io.Copy(c.Writer, resp.Body)
}

func (a *API) handleLyrics(c *gin.Context) {
	song := a.song(c.Param("source"), c.Param("id"))
	if song.Source == "netease" {
		if lrc := fetchNeteaseYRC(song.ID); lrc != "" {
			c.Data(http.StatusOK, "text/plain; charset=utf-8", []byte(lrc))
			return
		}
	}
	fn := core.GetLyricFunc(song.Source)
	if fn == nil {
		c.String(404, "")
		return
	}
	lrc, err := fn(song)
	if err != nil || lrc == "" {
		c.String(404, "")
		return
	}
	c.Header("Content-Type", "text/plain; charset=utf-8")
	c.String(200, lrc)
}

// handleInfo 公开返回服务器身份。带 nonce（base64url，16~64 字节）时附上对它的签名，
// App 用记下的公钥校验，确认是原来那台服务器后才发送自己的 token。
func (a *API) handleInfo(c *gin.Context) {
	if a.Identity == nil {
		c.JSON(404, gin.H{"error": "NOT_FOUND", "msg": "服务器没有身份密钥"})
		return
	}
	resp := gin.H{
		"serverId":  a.Identity.ID(),
		"publicKey": base64.StdEncoding.EncodeToString(a.Identity.PublicKey()),
	}
	if raw := c.Query("nonce"); raw != "" {
		nonce, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(raw, "="))
		if err != nil || len(nonce) < 16 || len(nonce) > 64 {
			c.JSON(400, gin.H{"error": "BAD_REQUEST", "msg": "nonce 需要是 16~64 字节的 base64url"})
			return
		}
		sig, err := a.Identity.Prove(nonce)
		if err != nil {
			c.JSON(500, gin.H{"error": "INTERNAL", "msg": err.Error()})
			return
		}
		resp["proof"] = base64.StdEncoding.EncodeToString(sig)
	}
	c.JSON(200, resp)
}

// addSong 在房间锁之外完成网络解析，避免一首歌慢慢解析时卡住整个房间。
func (a *API) addSong(userID string, r *room.Room, source, id string) (int, error) {
	if r.Kind() == room.KindVideo {
		return a.addVideo(userID, r, source, id)
	}
	if source == YouTubeSource {
		return 0, &room.Error{Code: room.CodeBadRequest, Msg: "YouTube 只能在放映厅点"}
	}
	song := a.song(source, id)
	if song.Name == "" {
		return 0, &room.Error{Code: room.CodeNotFound, Msg: "请重新搜索或刷新歌单后点歌"}
	}
	if _, err := a.Resolver.Lookup(song, false); err != nil {
		return 0, &room.Error{Code: room.CodeUnplayable, Msg: err.Error()}
	}
	return r.AddSong(userID, toTrack(song))
}

// warm 在锁定下一首时重新解析一次，提前刷新快过期的地址。
// 放映厅的 B 站视频也走这里：音轨能解析，视频轨基本也能，失败就一样移出待播。
func (a *API) warm(t room.TrackRef) error {
	if t.Source == YouTubeSource {
		// 每台手机自己解析，服务器这边没有地址要预热。
		return nil
	}
	_, err := a.Resolver.Lookup(a.song(t.Source, t.ID), false)
	return err
}

func (a *API) MountAdmin(g *gin.RouterGroup) {
	g.GET("/admin", func(c *gin.Context) {
		c.Header("Content-Type", "text/html; charset=utf-8")
		c.String(200, adminHTML)
	})
	g.GET("/invites", func(c *gin.Context) {
		list, err := a.Members.ListInvites()
		if err != nil {
			c.JSON(500, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, gin.H{"invites": list})
	})
	g.POST("/invites", func(c *gin.Context) {
		var req struct {
			MaxUses  *int `json:"maxUses"` // 不传默认 1 次，0 = 不限
			TTLHours int  `json:"ttlHours"`
		}
		_ = c.ShouldBindJSON(&req)
		maxUses := 1
		if req.MaxUses != nil && *req.MaxUses >= 0 {
			maxUses = *req.MaxUses
		}
		inv, err := a.Members.CreateInvite(maxUses, time.Duration(req.TTLHours)*time.Hour)
		if err != nil {
			c.JSON(500, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, inv)
	})
	g.GET("/members", func(c *gin.Context) {
		list, err := a.Members.ListMembers()
		if err != nil {
			c.JSON(500, gin.H{"error": err.Error()})
			return
		}
		c.JSON(200, gin.H{"members": list})
	})
	g.POST("/members/:id/disabled", func(c *gin.Context) {
		var req struct {
			Disabled bool `json:"disabled"`
		}
		_ = c.ShouldBindJSON(&req)
		if err := a.Members.SetDisabled(c.Param("id"), req.Disabled); err != nil {
			c.JSON(400, gin.H{"error": err.Error()})
			return
		}
		if req.Disabled {
			a.Hub.DisconnectUser(c.Param("id"))
		}
		c.JSON(200, gin.H{"ok": true})
	})
	g.GET("/rooms", func(c *gin.Context) { c.JSON(200, gin.H{"rooms": a.Rooms.List()}) })
	a.mountVideoAdmin(g)
	g.GET("/accounts", func(c *gin.Context) {
		cookies := core.CM.GetAll()
		out := make([]gin.H, 0, len(accountSources))
		for _, s := range accountSources {
			out = append(out, gin.H{"source": s.Source, "name": s.Name, "logins": s.Logins, "loggedIn": cookies[s.Source] != ""})
		}
		c.JSON(200, gin.H{"accounts": out})
	})
	g.POST("/accounts/:source/logout", func(c *gin.Context) {
		core.CM.SetAll(map[string]string{c.Param("source"): ""})
		core.CM.Save()
		c.JSON(200, gin.H{"ok": true})
	})
	// 扫码登录复用 go-music-dl 的 /qr_login 接口，这里只负责把登录链接画成二维码图片。
	g.GET("/qr.png", func(c *gin.Context) {
		png, err := qrcode.Encode(c.Query("text"), qrcode.Medium, 512)
		if err != nil {
			c.String(400, err.Error())
			return
		}
		c.Header("Cache-Control", "no-store")
		c.Data(200, "image/png", png)
	})
}

type accountSource struct {
	Source, Name string
	Logins       []qrLogin
}

// qrLogin 是一种扫码方式：QRSource 为 go-music-dl 扫码接口的平台名，App 是该用哪个 App 扫。
type qrLogin struct {
	QRSource string `json:"qrSource"`
	Label    string `json:"label"`
	App      string `json:"app"`
}

// accountSources 是管理页展示的平台。QQ 音乐的二维码是 QQ 登录码，要用手机 QQ 扫；
// 会员绑在微信上的账号得走微信扫码。
var accountSources = []accountSource{
	{"netease", "网易云音乐", []qrLogin{{"netease", "扫码登录", "网易云音乐 App"}}},
	{"qq", "QQ 音乐", []qrLogin{{"qq", "QQ 扫码", "手机 QQ"}, {"qq_wx", "微信扫码", "微信"}}},
	{"kugou", "酷狗音乐", []qrLogin{{"kugou", "扫码登录", "酷狗音乐 App"}}},
	{"bilibili", "哔哩哔哩", []qrLogin{{"bilibili", "扫码登录", "哔哩哔哩 App"}}},
}
