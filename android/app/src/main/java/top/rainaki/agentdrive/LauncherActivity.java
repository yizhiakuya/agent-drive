package top.rainaki.agentdrive;

/**
 * TWA 入口：全屏（免地址栏）打开 home.rainaki.top:13311。
 * 打开地址、assetlinks、状态栏色均在 AndroidManifest.xml 的 meta-data 中配置；
 * 分享接收也由 androidbrowserhelper 的 LauncherActivity 自动处理（POST 到 share_target）。
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
}
