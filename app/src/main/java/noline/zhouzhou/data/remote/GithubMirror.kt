package noline.zhouzhou.data.remote

/**
 * 更新包的取数来源：GitHub 直连，外加几个实测能用的公益加速站。
 *
 * 2026-10-15 用 v1.0.8 的 apk 逐个探过（`-r 0-63` 取首块，认 `PK\x03\x04` 而不是 HTML）：
 * gh-proxy.com 文件和 api.github.com 都能转；ghfast.top、ghproxy.net 只转文件，
 * 对 api 直接 403；github.moeyy.xyz、gh.llkk.cc、mirror.ghproxy.com、ghp.ci、
 * hub.gitmirror.com 已经连不上，gh.con.sh / gitproxy.click / ghproxy.cn / dlink.host
 * 返回的是 HTML 页面，所以都没收进来。
 *
 * 同一个 apk 走直连 240 秒只挪了 6.5 MB，gh-proxy.com 3.5 秒下完、ghfast.top 12 秒下完，
 * 两者 sha256 都与接口里的 digest 一致，所以默认来源选了 gh-proxy.com，直连留作一个选项。
 * 镜像站是会换的，下面 apiChain/downloadChain 的兜底顺序就是为这个准备的——一家死了还有下一家。
 */
enum class GithubMirror(
    val id: String,
    val label: String,
    /** 前缀拼接式代理；直连为空串。 */
    val prefix: String,
    /** 是否愿意转发 api.github.com。 */
    val apiReady: Boolean,
) {
    Direct("direct", "GitHub 直连", "", true),
    GhProxyCom("gh-proxy-com", "gh-proxy.com", "https://gh-proxy.com/", true),
    GhfastTop("ghfast-top", "ghfast.top", "https://ghfast.top/", false),
    GhproxyNet("ghproxy-net", "ghproxy.net", "https://ghproxy.net/", false);

    companion object {
        /** 认不出存的 id 就退回默认值，别让一个失效的选项把检查更新卡死。 */
        fun fromId(id: String?, default: GithubMirror = GhProxyCom): GithubMirror =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/** 查版本能试的来源：只挑能转发 api.github.com 的，顺序即声明顺序（直连在前）。 */
fun apiChain(): List<GithubMirror> = GithubMirror.entries.filter { it.apiReady }

/** 下载可用的来源：用户选的排最前，其余依次兜底。 */
fun downloadChain(preferred: GithubMirror): List<GithubMirror> =
    GithubMirror.entries.sortedBy { if (it == preferred) 0 else 1 }

/** 把原始地址改写成走某个来源；非 https（比如用户手输的 http 站点）不碰。 */
fun viaMirror(mirror: GithubMirror, url: String): String =
    if (mirror.prefix.isEmpty() || !url.startsWith("https://")) url else mirror.prefix + url
