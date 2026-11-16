# 编译期依赖：`libs/` 下的三个 jar

`libs/` 里的 jar 只参与编译，**不进 dex、不进 APK**（`build.gradle.kts` 里是 `compileOnly`）。
原因是这些类（`androidx.preference.*`、`androidx.fragment.*`、以及 framework 隐藏类）
在设备上由系统设置 APK 与 bootclasspath 提供；打进 APK 会与运行时的真实类同名，导致类加载失败。

| 文件 | 来源 | 是否入库 |
|---|---|---|
| `libs/hidden-stubs.jar` | 本仓库内维护的最小化占位（`InputChannel` / `InputEventReceiver` 等隐藏 API） | 是 |
| `libs/preference-classes.jar` | AndroidX `androidx.preference` 的 `classes.jar` | 否，需本地生成 |
| `libs/fragment-classes.jar` | AndroidX `androidx.fragment` 的 `classes.jar` | 否，需本地生成 |

后两个体积较大（约 170 KB + 330 KB）且每次 AAR 升级都会变，因此不入库；
**缺少它们时 `:app:assembleRelease` 会编译失败**（`Cannot resolve symbol` / 找不到类）。

## 生成方式

需要一次网络访问（Google Maven），之后完全离线。版本号只需包含用到的类即可，
API 自 1.2 / 1.0 起未变动，因此固定为下面的稳定版就够用：

```sh
# 在仓库根目录执行
mkdir -p libs

curl -sL -o /tmp/preference.aar \
  https://dl.google.com/android/maven2/androidx/preference/preference/1.2.1/preference-1.2.1.aar
unzip -p /tmp/preference.aar classes.jar > libs/preference-classes.jar

curl -sL -o /tmp/fragment.aar \
  https://dl.google.com/android/maven2/androidx/fragment/fragment/1.8.9/fragment-1.8.9.aar
unzip -p /tmp/fragment.aar classes.jar > libs/fragment-classes.jar

ls -l libs   # hidden-stubs.jar 应已存在；另两个是新生成的
```

校验（两个 jar 都不应为空，且能列出目标包）：

```sh
unzip -l libs/preference-classes.jar | grep -q 'androidx/preference/PreferenceFragmentCompat.class' && echo ok
unzip -l libs/fragment-classes.jar    | grep -q 'androidx/fragment/app/Fragment.class' && echo ok
```

## 注意事项

- 生成物**不要用 `git add -f`** 提交：`.gitignore` 已显式忽略这两个文件名，仓库保持轻量。
- 代码里对它们的引用必须保持 `compileOnly`；改成 `implementation` 会让 APK 里出现重复类。
- 设备系统设置 APK 里的 `androidx.preference` 版本与本地产物不一致时，优先按设备的类签名调整：
  缺少类或方法会在运行期表现为 `NoSuchMethodError`，此时用 `dexsql`/`jpype` 对照系统 APK 再生成。
