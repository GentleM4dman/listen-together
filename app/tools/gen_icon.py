# 生成 App 图标（两只猫共用一副耳机一起听歌）：python tools/gen_icon.py composeApp/src/androidMain/res
# 同时在当前目录输出 icon.svg 预览。
import sys

SVG_DEFS = """  <defs>
    <radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="54" cy="56" r="70">
      <stop offset="0" stop-color="#251D36"/>
      <stop offset="0.58" stop-color="#161220"/>
      <stop offset="1" stop-color="#0E0E12"/>
    </radialGradient>
    <radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="54" cy="56" r="42">
      <stop offset="0" stop-color="#FF4D5E" stop-opacity="0.28"/>
      <stop offset="0.55" stop-color="#FF8A4C" stop-opacity="0.10"/>
      <stop offset="1" stop-color="#FF4D5E" stop-opacity="0.00"/>
    </radialGradient>
    <linearGradient id="coral" gradientUnits="userSpaceOnUse" x1="30" y1="24" x2="78" y2="78">
      <stop offset="0" stop-color="#FF5F6D"/>
      <stop offset="1" stop-color="#FF8A4C"/>
    </linearGradient>
    <linearGradient id="band" gradientUnits="userSpaceOnUse" x1="54" y1="28" x2="54" y2="56">
      <stop offset="0" stop-color="#3E4054"/>
      <stop offset="1" stop-color="#242532"/>
    </linearGradient>
    <linearGradient id="cup" gradientUnits="userSpaceOnUse" x1="54" y1="50" x2="54" y2="74">
      <stop offset="0" stop-color="#2F3040"/>
      <stop offset="1" stop-color="#1A1B24"/>
    </linearGradient>
  </defs>"""

# 108x108 Adaptive Icon 安全区：以 (54, 54) 为圆心、半径 34 之内
SVG_BODY = """  <rect width="108" height="108" fill="url(#bg)"/>
  <circle cx="54" cy="56" r="40" fill="url(#glow)"/>

  <!-- 共用的头戴耳机头梁（跨过两只猫头顶） -->
  <path d="M23.5,58 C23.5,38 35.5,27.5 54,27.5 C72.5,27.5 84.5,38 84.5,58 L79.5,58 C79.5,41 69.5,32.5 54,32.5 C38.5,32.5 28.5,41 28.5,58 Z" fill="url(#band)"/>
  <path d="M26.5,53 C28.5,39.5 39.5,30.2 54,30.2 C68.5,30.2 79.5,39.5 81.5,53" fill="none" stroke="#53556E" stroke-width="1.4" stroke-linecap="round" opacity="0.75"/>

  <!-- 飘在两只猫中间的双八分音符（点题「一起听歌」） -->
  <path d="M51.2,43.5 L51.2,34.8 L60.2,32.8 L60.2,41.6" fill="none" stroke="url(#coral)" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M51.2,37.4 L60.2,35.4" fill="none" stroke="url(#coral)" stroke-width="2.1" stroke-linecap="round"/>
  <ellipse cx="49.2" cy="43.8" rx="3.1" ry="2.3" transform="rotate(-18 49.2 43.8)" fill="url(#coral)"/>
  <ellipse cx="58.2" cy="41.8" rx="3.1" ry="2.3" transform="rotate(-18 58.2 41.8)" fill="url(#coral)"/>

  <!-- 右侧深灰蓝小猫（略向左倾，依偎在一起） -->
  <g>
    <!-- 身子与小爪子 -->
    <path d="M53,82 C54,73.5 60,69.5 68.5,69.5 C76.5,69.5 81.5,73.5 82,82 Z" fill="#323548"/>
    <!-- 右猫左耳与右耳 -->
    <path d="M57.5,53.5 C56.5,48.5 57.6,43.6 59.5,42.5 C61.1,41.6 64.5,45.5 66.2,49.8 Z" fill="#3B3E54"/>
    <path d="M76.5,55 C78.5,50.5 79.2,45.6 77.6,44.2 C76,42.8 72.2,45.8 69.8,49.8 Z" fill="#3B3E54"/>
    <path d="M59,51.6 C58.6,48.6 59.2,45.8 60.2,45 C61.4,45.5 63.2,47.8 64.2,50.2 Z" fill="#FFA4B6" opacity="0.85"/>
    <path d="M75.4,52.8 C76.5,49.8 76.7,47.2 75.8,46.2 C74.5,46.5 72.6,48.5 71.5,50.8 Z" fill="#FFA4B6" opacity="0.85"/>
    <!-- 头部轮廓 -->
    <path d="M53.5,62 C53.5,53.2 60.2,47.8 68.4,48.4 C76.4,49 81.6,55.2 81,63.2 C80.4,71.2 74,76.2 66.4,75.8 C58.8,75.4 53.5,70.2 53.5,62 Z" fill="#3B3E54"/>
    <!-- 奶油色嘴巴绒毛区 -->
    <path d="M59.2,68.2 C59.4,64.2 63.2,62 67.2,62.4 C71.4,62.8 74.6,65.6 74.4,69.2 C74.2,72.8 70.6,75.2 66.5,75 C62.4,74.8 59,71.8 59.2,68.2 Z" fill="#FFEFE0"/>
    <!-- 闭眼陶醉听歌（◡ ◡） -->
    <path d="M59.2,59.8 C60.5,61.6 62.9,61.7 64.3,60.1" fill="none" stroke="#FFF2E4" stroke-width="2" stroke-linecap="round"/>
    <path d="M69.2,60.5 C70.5,62.3 72.9,62.4 74.3,60.8" fill="none" stroke="#FFF2E4" stroke-width="2" stroke-linecap="round"/>
    <!-- 小鼻子与微笑 :3 -->
    <path d="M65.3,65.2 C65.8,64.4 67.8,64.5 68.3,65.4 C68,66.4 67.2,67 66.7,67 C66.2,67 65.5,66.2 65.3,65.2 Z" fill="#FF6B81"/>
    <path d="M63.8,68.6 C64.8,69.8 66.2,69.6 66.7,68.2 C67.2,69.6 68.6,69.9 69.6,68.8" fill="none" stroke="#3B3E54" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
    <!-- 奶油小爪 -->
    <ellipse cx="62" cy="79.5" rx="3.4" ry="2.4" fill="#FFEFE0"/>
    <ellipse cx="73" cy="79.5" rx="3.4" ry="2.4" fill="#FFEFE0"/>
  </g>

  <!-- 左侧暖奶油橘小猫（略向右倾，毛茸茸暖色调） -->
  <g>
    <!-- 身子 -->
    <path d="M26,82 C26.5,73.5 31.5,69.5 39.5,69.5 C48,69.5 54,73.5 55,82 Z" fill="#F5D3B3"/>
    <!-- 左猫左耳与右耳 -->
    <path d="M31.5,55 C29.5,50.5 28.8,45.6 30.4,44.2 C32,42.8 35.8,45.8 38.2,49.8 Z" fill="#FFEAD5"/>
    <path d="M50.5,53.5 C51.5,48.5 50.4,43.6 48.5,42.5 C46.9,41.6 43.5,45.5 41.8,49.8 Z" fill="#FFEAD5"/>
    <path d="M32.6,52.8 C31.5,49.8 31.3,47.2 32.2,46.2 C33.5,46.5 35.4,48.5 36.5,50.8 Z" fill="#FF9BB0"/>
    <path d="M49,51.6 C49.4,48.6 48.8,45.8 47.8,45 C46.6,45.5 44.8,47.8 43.8,50.2 Z" fill="#FF9BB0"/>
    <!-- 头部轮廓（带轻微毛茸脸颊） -->
    <path d="M27,63.2 C26.4,55.2 31.6,49 39.6,48.4 C47.8,47.8 54.5,53.2 54.5,62 C54.5,70.2 49.2,75.4 41.6,75.8 C34,76.2 27.6,71.2 27,63.2 Z" fill="#FFEAD5"/>
    <!-- 额头小虎斑纹理 -->
    <path d="M38.5,49.8 L39.2,52.8 M41.8,49.4 L41.5,52.6 M44.8,50.4 L43.8,53.2" fill="none" stroke="#EAB88C" stroke-width="1.3" stroke-linecap="round" opacity="0.75"/>
    <!-- 闭眼陶醉听歌（^ ^ 柔和弧线） -->
    <path d="M33.6,61 C34.8,59 37.2,58.8 38.6,60.4" fill="none" stroke="#2C2238" stroke-width="2" stroke-linecap="round"/>
    <path d="M43.6,60.2 C45,58.6 47.4,58.7 48.6,60.6" fill="none" stroke="#2C2238" stroke-width="2" stroke-linecap="round"/>
    <!-- 腮红 -->
    <ellipse cx="33.4" cy="65.2" rx="2.7" ry="1.5" fill="#FFA6B8" opacity="0.75"/>
    <ellipse cx="49" cy="64.8" rx="2.7" ry="1.5" fill="#FFA6B8" opacity="0.75"/>
    <!-- 小鼻子与开心小嘴 -->
    <path d="M39.8,64.8 C40.3,64 42.3,64 42.8,64.8 C42.5,65.9 41.7,66.5 41.3,66.5 C40.8,66.5 40.1,65.8 39.8,64.8 Z" fill="#FF6B81"/>
    <path d="M38.6,68.2 C39.6,69.5 41,69.3 41.4,67.9 C41.8,69.3 43.2,69.5 44.2,68.2" fill="none" stroke="#2C2238" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
    <!-- 暖奶油小爪 -->
    <ellipse cx="35" cy="79.5" rx="3.4" ry="2.4" fill="#FFF4E8"/>
    <ellipse cx="46" cy="79.5" rx="3.4" ry="2.4" fill="#FFF4E8"/>
  </g>

  <!-- 两侧外耳罩（左耳罩在左猫左耳，右耳罩在右猫右耳，珊瑚橙灯环点亮） -->
  <rect x="20.5" y="51.5" width="9.5" height="21" rx="4.7" fill="url(#cup)"/>
  <rect x="78" y="51.5" width="9.5" height="21" rx="4.7" fill="url(#cup)"/>
  <ellipse cx="28.2" cy="62" rx="2.4" ry="7.4" fill="#14141B"/>
  <ellipse cx="79.8" cy="62" rx="2.4" ry="7.4" fill="#14141B"/>
  <path d="M23.5,56 C22.3,59.5 22.3,64.5 23.5,68" fill="none" stroke="url(#coral)" stroke-width="2.2" stroke-linecap="round"/>
  <path d="M84.5,56 C85.7,59.5 85.7,64.5 84.5,68" fill="none" stroke="url(#coral)" stroke-width="2.2" stroke-linecap="round"/>"""


def svg():
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">\n'
        f"{SVG_DEFS}\n"
        f"{SVG_BODY}\n"
        "</svg>\n"
    )


def bg_xml():
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_icon.py 生成，改图标请改那里 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="56" android:gradientRadius="70">
                <item android:offset="0" android:color="#FF251D36" />
                <item android:offset="0.58" android:color="#FF161220" />
                <item android:offset="1" android:color="#FF0E0E12" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M14,56a40,40 0 1,0 80,0a40,40 0 1,0 -80,0">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="56" android:gradientRadius="42">
                <item android:offset="0" android:color="#47FF4D5E" />
                <item android:offset="0.55" android:color="#1AFF8A4C" />
                <item android:offset="1" android:color="#00FF4D5E" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def fg_xml():
    return """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_icon.py 生成，改图标请改那里 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- 共用的头戴耳机头梁 -->
    <path android:pathData="M23.5,58 C23.5,38 35.5,27.5 54,27.5 C72.5,27.5 84.5,38 84.5,58 L79.5,58 C79.5,41 69.5,32.5 54,32.5 C38.5,32.5 28.5,41 28.5,58 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="54" android:startY="28" android:endX="54" android:endY="56">
                <item android:offset="0" android:color="#FF3E4054" />
                <item android:offset="1" android:color="#FF242532" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:strokeColor="#FF53556E" android:strokeAlpha="0.75" android:strokeWidth="1.4" android:strokeLineCap="round"
        android:pathData="M26.5,53 C28.5,39.5 39.5,30.2 54,30.2 C68.5,30.2 79.5,39.5 81.5,53" />

    <!-- 双八分音符 -->
    <path android:strokeWidth="2.3" android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M51.2,43.5 L51.2,34.8 L60.2,32.8 L60.2,41.6">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="46" android:startY="32" android:endX="62" android:endY="46">
                <item android:offset="0" android:color="#FFFF5F6D" />
                <item android:offset="1" android:color="#FFFF8A4C" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:strokeWidth="2.1" android:strokeLineCap="round" android:pathData="M51.2,37.4 L60.2,35.4">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="46" android:startY="32" android:endX="62" android:endY="46">
                <item android:offset="0" android:color="#FFFF5F6D" />
                <item android:offset="1" android:color="#FFFF8A4C" />
            </gradient>
        </aapt:attr>
    </path>
    <group android:pivotX="49.2" android:pivotY="43.8" android:rotation="-18">
        <path android:pathData="M46.1,43.8a3.1,2.3 0 1,0 6.2,0a3.1,2.3 0 1,0 -6.2,0">
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="46" android:startY="32" android:endX="62" android:endY="46">
                    <item android:offset="0" android:color="#FFFF5F6D" />
                    <item android:offset="1" android:color="#FFFF8A4C" />
                </gradient>
            </aapt:attr>
        </path>
    </group>
    <group android:pivotX="58.2" android:pivotY="41.8" android:rotation="-18">
        <path android:pathData="M55.1,41.8a3.1,2.3 0 1,0 6.2,0a3.1,2.3 0 1,0 -6.2,0">
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="46" android:startY="32" android:endX="62" android:endY="46">
                    <item android:offset="0" android:color="#FFFF5F6D" />
                    <item android:offset="1" android:color="#FFFF8A4C" />
                </gradient>
            </aapt:attr>
        </path>
    </group>

    <!-- 右侧深灰蓝小猫 -->
    <path android:fillColor="#FF323548" android:pathData="M53,82 C54,73.5 60,69.5 68.5,69.5 C76.5,69.5 81.5,73.5 82,82 Z" />
    <path android:fillColor="#FF3B3E54" android:pathData="M57.5,53.5 C56.5,48.5 57.6,43.6 59.5,42.5 C61.1,41.6 64.5,45.5 66.2,49.8 Z" />
    <path android:fillColor="#FF3B3E54" android:pathData="M76.5,55 C78.5,50.5 79.2,45.6 77.6,44.2 C76,42.8 72.2,45.8 69.8,49.8 Z" />
    <path android:fillColor="#FFFFA4B6" android:fillAlpha="0.85" android:pathData="M59,51.6 C58.6,48.6 59.2,45.8 60.2,45 C61.4,45.5 63.2,47.8 64.2,50.2 Z" />
    <path android:fillColor="#FFFFA4B6" android:fillAlpha="0.85" android:pathData="M75.4,52.8 C76.5,49.8 76.7,47.2 75.8,46.2 C74.5,46.5 72.6,48.5 71.5,50.8 Z" />
    <path android:fillColor="#FF3B3E54" android:pathData="M53.5,62 C53.5,53.2 60.2,47.8 68.4,48.4 C76.4,49 81.6,55.2 81,63.2 C80.4,71.2 74,76.2 66.4,75.8 C58.8,75.4 53.5,70.2 53.5,62 Z" />
    <path android:fillColor="#FFFFEFE0" android:pathData="M59.2,68.2 C59.4,64.2 63.2,62 67.2,62.4 C71.4,62.8 74.6,65.6 74.4,69.2 C74.2,72.8 70.6,75.2 66.5,75 C62.4,74.8 59,71.8 59.2,68.2 Z" />
    <path android:strokeColor="#FFFFF2E4" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M59.2,59.8 C60.5,61.6 62.9,61.7 64.3,60.1" />
    <path android:strokeColor="#FFFFF2E4" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M69.2,60.5 C70.5,62.3 72.9,62.4 74.3,60.8" />
    <path android:fillColor="#FFFF6B81" android:pathData="M65.3,65.2 C65.8,64.4 67.8,64.5 68.3,65.4 C68,66.4 67.2,67 66.7,67 C66.2,67 65.5,66.2 65.3,65.2 Z" />
    <path android:strokeColor="#FF3B3E54" android:strokeWidth="1.4" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M63.8,68.6 C64.8,69.8 66.2,69.6 66.7,68.2 C67.2,69.6 68.6,69.9 69.6,68.8" />
    <path android:fillColor="#FFFFEFE0" android:pathData="M58.6,79.5a3.4,2.4 0 1,0 6.8,0a3.4,2.4 0 1,0 -6.8,0" />
    <path android:fillColor="#FFFFEFE0" android:pathData="M69.6,79.5a3.4,2.4 0 1,0 6.8,0a3.4,2.4 0 1,0 -6.8,0" />

    <!-- 左侧暖奶油橘小猫 -->
    <path android:fillColor="#FFF5D3B3" android:pathData="M26,82 C26.5,73.5 31.5,69.5 39.5,69.5 C48,69.5 54,73.5 55,82 Z" />
    <path android:fillColor="#FFFFEAD5" android:pathData="M31.5,55 C29.5,50.5 28.8,45.6 30.4,44.2 C32,42.8 35.8,45.8 38.2,49.8 Z" />
    <path android:fillColor="#FFFFEAD5" android:pathData="M50.5,53.5 C51.5,48.5 50.4,43.6 48.5,42.5 C46.9,41.6 43.5,45.5 41.8,49.8 Z" />
    <path android:fillColor="#FFFF9BB0" android:pathData="M32.6,52.8 C31.5,49.8 31.3,47.2 32.2,46.2 C33.5,46.5 35.4,48.5 36.5,50.8 Z" />
    <path android:fillColor="#FFFF9BB0" android:pathData="M49,51.6 C49.4,48.6 48.8,45.8 47.8,45 C46.6,45.5 44.8,47.8 43.8,50.2 Z" />
    <path android:fillColor="#FFFFEAD5" android:pathData="M27,63.2 C26.4,55.2 31.6,49 39.6,48.4 C47.8,47.8 54.5,53.2 54.5,62 C54.5,70.2 49.2,75.4 41.6,75.8 C34,76.2 27.6,71.2 27,63.2 Z" />
    <path android:strokeColor="#FFEAB88C" android:strokeAlpha="0.75" android:strokeWidth="1.3" android:strokeLineCap="round" android:pathData="M38.5,49.8 L39.2,52.8 M41.8,49.4 L41.5,52.6 M44.8,50.4 L43.8,53.2" />
    <path android:strokeColor="#FF2C2238" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M33.6,61 C34.8,59 37.2,58.8 38.6,60.4" />
    <path android:strokeColor="#FF2C2238" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M43.6,60.2 C45,58.6 47.4,58.7 48.6,60.6" />
    <path android:fillColor="#FFFFA6B8" android:fillAlpha="0.75" android:pathData="M30.7,65.2a2.7,1.5 0 1,0 5.4,0a2.7,1.5 0 1,0 -5.4,0" />
    <path android:fillColor="#FFFFA6B8" android:fillAlpha="0.75" android:pathData="M46.3,64.8a2.7,1.5 0 1,0 5.4,0a2.7,1.5 0 1,0 -5.4,0" />
    <path android:fillColor="#FFFF6B81" android:pathData="M39.8,64.8 C40.3,64 42.3,64 42.8,64.8 C42.5,65.9 41.7,66.5 41.3,66.5 C40.8,66.5 40.1,65.8 39.8,64.8 Z" />
    <path android:strokeColor="#FF2C2238" android:strokeWidth="1.4" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M38.6,68.2 C39.6,69.5 41,69.3 41.4,67.9 C41.8,69.3 43.2,69.5 44.2,68.2" />
    <path android:fillColor="#FFFFF4E8" android:pathData="M31.6,79.5a3.4,2.4 0 1,0 6.8,0a3.4,2.4 0 1,0 -6.8,0" />
    <path android:fillColor="#FFFFF4E8" android:pathData="M42.6,79.5a3.4,2.4 0 1,0 6.8,0a3.4,2.4 0 1,0 -6.8,0" />

    <!-- 两侧外耳罩 -->
    <path android:pathData="M25.2,51.5H25.3A4.7,4.7 0 0,1 30,56.2V67.8A4.7,4.7 0 0,1 25.3,72.5H25.2A4.7,4.7 0 0,1 20.5,67.8V56.2A4.7,4.7 0 0,1 25.2,51.5Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="25" android:startY="51" android:endX="25" android:endY="73">
                <item android:offset="0" android:color="#FF2F3040" />
                <item android:offset="1" android:color="#FF1A1B24" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M82.7,51.5H82.8A4.7,4.7 0 0,1 87.5,56.2V67.8A4.7,4.7 0 0,1 82.8,72.5H82.7A4.7,4.7 0 0,1 78,67.8V56.2A4.7,4.7 0 0,1 82.7,51.5Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="83" android:startY="51" android:endX="83" android:endY="73">
                <item android:offset="0" android:color="#FF2F3040" />
                <item android:offset="1" android:color="#FF1A1B24" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:fillColor="#FF14141B" android:pathData="M25.8,62a2.4,7.4 0 1,0 4.8,0a2.4,7.4 0 1,0 -4.8,0" />
    <path android:fillColor="#FF14141B" android:pathData="M77.4,62a2.4,7.4 0 1,0 4.8,0a2.4,7.4 0 1,0 -4.8,0" />
    <path android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="M23.5,56 C22.3,59.5 22.3,64.5 23.5,68">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="23" android:startY="55" android:endX="23" android:endY="69">
                <item android:offset="0" android:color="#FFFF5F6D" />
                <item android:offset="1" android:color="#FFFF8A4C" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="M84.5,56 C85.7,59.5 85.7,64.5 84.5,68">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="85" android:startY="55" android:endX="85" android:endY="69">
                <item android:offset="0" android:color="#FFFF5F6D" />
                <item android:offset="1" android:color="#FFFF8A4C" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


open("icon.svg", "w", encoding="utf-8").write(svg())
if len(sys.argv) > 1:
    res = sys.argv[1]
    open(f"{res}/drawable/ic_launcher_foreground.xml", "w", encoding="utf-8").write(fg_xml())
    open(f"{res}/drawable/ic_launcher_background.xml", "w", encoding="utf-8").write(bg_xml())
