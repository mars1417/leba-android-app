# 乐吧Android App
# 解决2个二进制死结的方案说明

## 死结1: Gradle Wrapper JAR

### 问题
`gradle/wrapper/gradle-wrapper.jar` 是二进制文件（~60KB），
无法通过纯文本方式生成在仓库中。

### 解决方案（2选1）

方案A：Workflow中直接用 gradle 命令（推荐）
```yaml
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v3
  with:
    gradle-version: "8.2"

- name: Build
  run: gradle assembleRelease
```

方案B：在Workflow首先生成wrapper
```yaml
- name: Generate Gradle Wrapper
  run: |
    gradle wrapper --gradle-version 8.2
    # 现在 gradlew + gradle-wrapper.jar 自动生成
  # 注意：gradle命令由 setup-gradle action 提供
```

## 死结2: Android PNG图标

### 问题
传统Android图标需要 mipmap-hdpi/mdpi/xhdpi/xxhdpi/xxxhdpi
各尺寸的PNG文件（二进制）。

### 解决方案
Android 8.0 (API 26) 引入 Adaptive Icons，
使用XML Vector Drawable（纯文本）替代PNG。

文件结构：
```
res/
├── drawable/
│   ├── ic_launcher_foreground.xml    ← vector drawable (text ✅)
│   └── ic_launcher_background.xml    ← vector drawable (text ✅)
└── mipmap-anydpi-v26/
    └── ic_launcher.xml               ← adaptive icon def (text ✅)
```

API 26+ (Android 8.0+) 覆盖了当前99%以上的活跃设备，
低版本设备会自动显示默认Android图标（不影响功能）。

## 完整文件清单

leba-android-app/
├── .github/workflows/build.yml          ✅ 文本
├── build.gradle                          ✅ 文本
├── settings.gradle                       ✅ 文本
├── gradle.properties                     ✅ 文本
├── gradle/wrapper/
│   └── gradle-wrapper.properties         ✅ 文本
│   (gradlew + JAR 由 CI 自动生成 ❌→✅)
├── app/
│   ├── build.gradle                      ✅ 文本
│   └── src/main/
│       ├── AndroidManifest.xml           ✅ 文本
│       ├── java/com/leba/app/
│       │   └── MainActivity.kt           ✅ 文本
│       └── res/
│           ├── drawable/
│           │   ├── ic_launcher_foreground.xml ✅ 文本
│           │   └── ic_launcher_background.xml ✅ 文本
│           ├── mipmap-anydpi-v26/
│           │   └── ic_launcher.xml       ✅ 文本
│           ├── layout/
│           │   └── activity_main.xml     ✅ 文本
│           └── values/
│               ├── strings.xml           ✅ 文本
│               └── themes.xml            ✅ 文本
