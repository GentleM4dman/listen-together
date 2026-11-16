package appapi

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
)

var (
	yrcLinePattern        = regexp.MustCompile(`^\[(\d+),(\d+)\]`)
	yrcWordPattern        = regexp.MustCompile(`\((\d+),\d+,\d+\)`)
	lrcTranslationPattern = regexp.MustCompile(`^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\](.*)$`)
	neteaseYRCClient      = &http.Client{Timeout: 5 * time.Second}
)

type yrcTranslation struct {
	ms   int64
	text string
	used bool
}

func lyricTimestamp(ms int64) string {
	return fmt.Sprintf("%02d:%02d.%03d", ms/60000, ms/1000%60, ms%1000)
}

func convertYRCToEnhancedLRC(yrc string, tlyric string) string {
	var translations []yrcTranslation
	for _, line := range strings.Split(tlyric, "\n") {
		match := lrcTranslationPattern.FindStringSubmatch(strings.TrimSpace(line))
		if match == nil || strings.TrimSpace(match[4]) == "" {
			continue
		}
		min, _ := strconv.ParseInt(match[1], 10, 64)
		sec, _ := strconv.ParseInt(match[2], 10, 64)
		fraction := match[3]
		for len(fraction) < 3 {
			fraction += "0"
		}
		ms, _ := strconv.ParseInt(fraction, 10, 64)
		translations = append(translations, yrcTranslation{ms: min*60000 + sec*1000 + ms, text: match[4]})
	}

	var output strings.Builder
	for _, raw := range strings.Split(yrc, "\n") {
		line := strings.TrimRight(raw, "\r")
		header := yrcLinePattern.FindStringSubmatchIndex(line)
		if header == nil {
			continue
		}
		start, err := strconv.ParseInt(line[header[2]:header[3]], 10, 64)
		if err != nil {
			continue
		}
		duration, err := strconv.ParseInt(line[header[4]:header[5]], 10, 64)
		if err != nil || duration > int64(^uint64(0)>>1)-start {
			continue
		}
		body := line[header[1]:]
		words := yrcWordPattern.FindAllStringSubmatchIndex(body, -1)
		if len(words) == 0 {
			continue
		}
		var content strings.Builder
		valid := true
		for i, word := range words {
			wordStart, err := strconv.ParseInt(body[word[2]:word[3]], 10, 64)
			if err != nil {
				valid = false
				break
			}
			end := len(body)
			if i+1 < len(words) {
				end = words[i+1][0]
			}
			fmt.Fprintf(&content, "<%s>%s", lyricTimestamp(wordStart), body[word[1]:end])
		}
		if !valid || strings.TrimSpace(yrcWordPattern.ReplaceAllString(body, "")) == "" {
			continue
		}
		stamp := lyricTimestamp(start)
		fmt.Fprintf(&output, "[%s]%s[%s]\n", stamp, content.String(), lyricTimestamp(start+duration))

		best, bestDistance := -1, int64(501)
		for i := range translations {
			if translations[i].used {
				continue
			}
			distance := translations[i].ms - start
			if distance < 0 {
				distance = -distance
			}
			if distance < bestDistance {
				best, bestDistance = i, distance
			}
		}
		if best >= 0 {
			translations[best].used = true
			fmt.Fprintf(&output, "[%s]%s\n", stamp, translations[best].text)
		}
	}
	return output.String()
}

func fetchNeteaseYRC(songID string) string {
	query := url.Values{"id": {songID}, "cp": {"false"}, "tv": {"1"}, "lv": {"1"}, "rv": {"0"}, "kv": {"0"}, "yv": {"1"}, "ytv": {"1"}, "yrv": {"0"}}
	req, err := http.NewRequest(http.MethodGet, "https://music.163.com/api/song/lyric/v1?"+query.Encode(), nil)
	if err != nil {
		return ""
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
	req.Header.Set("Referer", "https://music.163.com/")
	if cookie := core.CM.Get("netease"); cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	resp, err := neteaseYRCClient.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ""
	}
	var result struct {
		YRC struct {
			Lyric string `json:"lyric"`
		} `json:"yrc"`
		YTLRC struct {
			Lyric string `json:"lyric"`
		} `json:"ytlrc"`
		TLyric struct {
			Lyric string `json:"lyric"`
		} `json:"tlyric"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&result) != nil || result.YRC.Lyric == "" {
		return ""
	}
	translation := result.YTLRC.Lyric
	if strings.TrimSpace(translation) == "" {
		translation = result.TLyric.Lyric
	}
	return convertYRCToEnhancedLRC(result.YRC.Lyric, translation)
}
