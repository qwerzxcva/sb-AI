package com.sbai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

// ---------------------------------------------------------------------------
// Route rules
// ---------------------------------------------------------------------------

@Serializable
enum class RuleAction(val displayName: String) {
    /** 路由到代理出口 */
    ROUTE_PROXY("代理"),

    /** 路由到直连 */
    ROUTE_DIRECT("直连"),

    /** 拦截（reject） */
    REJECT("拦截"),

    /** 仅嗅探协议，不改路由 */
    SNIFF("嗅探"),

    /** 仅解析 DNS，不改路由 */
    RESOLVE("仅解析 DNS"),

    /** 劫持为 DNS 查询 */
    HIJACK_DNS("劫持 DNS"),

    /** 仅修改路由选项 */
    ROUTE_OPTIONS("路由选项"),
}

/** 逻辑运算：AND（全部满足）/ OR（任一满足）。
 *  sing-box 语义：同一个 rule 对象内各字段是 AND，字段内数组是 OR。
 *  因此 AND = 平铺单条 rule；OR = logical{mode:or, rules:[按字段类别拆分的子规则]}。 */
@Serializable
enum class RuleLogic(val wireName: String?, val displayName: String) {
    AND("and", "AND（全部满足）"),
    OR("or", "OR（任一满足）"),
}

@Serializable
data class RouteRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val action: RuleAction = RuleAction.ROUTE_PROXY,
    /** 路由目标 outbound tag：空 = 跟随动作语义（代理用 entryTag，直连接直接；其他动作忽略此字段） */
    val outboundTag: String = "",

    // 一行一条：域名 / 域名后缀 / IP / IP CIDR / 远程规则集 tag
    val domains: List<String> = emptyList(),          // 精确域名
    val domainSuffixes: List<String> = emptyList(),   // 域名后缀
    val domainKeywords: List<String> = emptyList(),   // 域名关键词
    val domainRegexes: List<String> = emptyList(),    // 域名正则
    /** 内置 geosite 分类（如 cn / geolocation-!cn / private）；内核内置，无需远程下载 */
    val geosites: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),          // 目标 IP / CIDR（IP 规则）
    /** 内置 geoip 分类（如 cn / private）；内核内置，无需远程下载 */
    val geoips: List<String> = emptyList(),
    val ruleSetTags: List<String> = emptyList(),      // 引用的规则集（含远程 IP 规则集 / URL）

    // network / protocol 允许多选
    val networks: List<String> = emptyList(),         // tcp / udp / icmp
    val protocols: List<String> = emptyList(),        // http / tls / quic / dns / ...
    /** IP 版本限制：空 = 不限；4 / 6 = 只匹配对应版本 */
    val ipVersion: Int = 0,

    val ports: List<Int> = emptyList(),               // 目标端口
    val portRanges: List<String> = emptyList(),       // 目标端口段 "8000:9000"

    // ---- 全量字段（源侧 / 进程 / 应用 / 网络环境）----
    // 注意：以下字段需要 root 权限才能在 Android 上生效，UI 中默认隐藏
    val sourceIpCidrs: List<String> = emptyList(),    // 源 IP / CIDR
    val sourcePorts: List<Int> = emptyList(),         // 源端口
    val sourcePortRanges: List<String> = emptyList(), // 源端口段
    val packageNames: List<String> = emptyList(),     // 应用包名 (需 root)
    val packageNameRegexes: List<String> = emptyList(), // 应用包名正则（兼容旧格式）
    val processNames: List<String> = emptyList(),     // 进程名 (需 root)
    val processPaths: List<String> = emptyList(),     // 进程路径 (需 root)
    val processPathRegexes: List<String> = emptyList(), // 进程路径正则
    val users: List<String> = emptyList(),            // 用户名 (需 root)
    val userIds: List<Int> = emptyList(),             // 用户 ID (需 root)
    val networkTypes: List<String> = emptyList(),     // wifi / cellular / ethernet
    val wifiSsids: List<String> = emptyList(),        // WiFi SSID
    val wifiBssids: List<String> = emptyList(),       // WiFi BSSID
    val inbounds: List<String> = emptyList(),         // 入站 tag
    val clashMode: String = "",                       // Clash 模式（rule/global/direct）
    val sourceIpIsPrivate: Boolean = false,           // 源 IP 是私有地址
    val ipIsPrivate: Boolean = false,                 // 目标 IP 是私有地址
    val networkIsExpensive: Boolean = false,          // 计费网络
    /** 拦截方式（仅 BLOCK）：default=返回拒绝 / drop=直接丢弃 */
    val rejectMethod: String = "default",

    // ---- route-options 动作字段（仅 ROUTE_OPTIONS；内核要求至少一个，否则拒绝配置）----
    /** 覆盖目标地址 */
    val overrideAddress: String = "",
    /** 覆盖目标端口；0 = 不覆盖 */
    val overridePort: Int = 0,
    /** TLS ClientHello 分片（tls.fragment） */
    val tlsFragment: Boolean = false,
    /** TLS 记录分片（tls.record_fragment） */
    val tlsRecordFragment: Boolean = false,
    /** TLS 分片回退延迟（duration 字符串） */
    val tlsFragmentFallbackDelay: String = "",
    /** UDP connect：先建立 UDP 会话再转发，解决部分运营商 UDP 阻断 */
    val udpConnect: Boolean = false,
    /** UDP 超时（duration 字符串，如 "5m"）；空 = 内核默认 */
    val udpTimeout: String = "",
    /** 关闭 UDP 域名映射：响应里回原始地址而非映射域名（兼容 Surge 类客户端） */
    val udpDisableDomainUnmapping: Boolean = false,

    val logic: RuleLogic = RuleLogic.AND,
    val invert: Boolean = false,                      // 逻辑运算 invert

    // IPv4 / IPv6 勾选（默认全部勾选）
    val ipv4: Boolean = true,
    val ipv6: Boolean = true,

    // 直连/代理可指定 DNS 或 DNS group（不强制）；拦截类/IP 规则/远程规则集规则不使用
    val dnsTag: String? = null,                       // DNS server tag 或 DNS group 名
) {
    /** 仅由 IP CIDR / 远程规则集构成的规则不生成 DNS 联动 */
    val isIpOrRemoteOnly: Boolean
        get() = domains.isEmpty() && domainSuffixes.isEmpty() &&
                domainKeywords.isEmpty() && domainRegexes.isEmpty() &&
                (ipCidrs.isNotEmpty() || ruleSetTags.isNotEmpty())

    val hasDomainContent: Boolean
        get() = domains.isNotEmpty() || domainSuffixes.isNotEmpty() ||
                domainKeywords.isNotEmpty() || domainRegexes.isNotEmpty() ||
                geosites.isNotEmpty()

    /**
     * 是否填了任何匹配条件。
     * 全空的路由规则在 sing-box 里是「匹配全部流量」，会遮蔽 final 与后续规则，
     * 生成器直接跳过这种规则。
     */
    val hasAnyCondition: Boolean
        get() = hasDomainContent || ipCidrs.isNotEmpty() || geoips.isNotEmpty() ||
                ruleSetTags.isNotEmpty() ||
                networks.isNotEmpty() || protocols.isNotEmpty() || ports.isNotEmpty() ||
                portRanges.isNotEmpty() || sourceIpCidrs.isNotEmpty() ||
                sourcePorts.isNotEmpty() || sourcePortRanges.isNotEmpty() ||
                packageNames.isNotEmpty() || packageNameRegexes.isNotEmpty() ||
                processNames.isNotEmpty() || processPaths.isNotEmpty() ||
                processPathRegexes.isNotEmpty() || users.isNotEmpty() ||
                userIds.isNotEmpty() || networkTypes.isNotEmpty() ||
                wifiSsids.isNotEmpty() || wifiBssids.isNotEmpty() ||
                inbounds.isNotEmpty() || sourceIpIsPrivate || ipIsPrivate ||
                networkIsExpensive

    /** ROUTE_OPTIONS 是否填了至少一个内核认可的字段（空 route-options 会被拒绝启动） */
    val hasRouteOptionsFields: Boolean
        get() = overrideAddress.isNotBlank() || overridePort > 0 ||
                tlsFragment || tlsRecordFragment ||
                udpConnect || udpTimeout.isNotBlank() || udpDisableDomainUnmapping
}

/** 路由规则集（可勾选 IPv4 / IPv6，默认全选） */
@Serializable
data class RouteRuleSet(
    val id: String = UUID.randomUUID().toString(),
    val tag: String = "",
    val enabled: Boolean = true,
    val type: RuleSetType = RuleSetType.REMOTE,
    val url: String = "",            // remote
    val localContent: String = "",   // local：source 格式 JSON 内容
    val downloadDetour: String? = null,
    val ipv4: Boolean = true,
    val ipv6: Boolean = true,
)

@Serializable
enum class RuleSetType { LOCAL, REMOTE }

// ---------------------------------------------------------------------------
// DNS
// ---------------------------------------------------------------------------

@Serializable
data class DnsServer(
    val id: String = UUID.randomUUID().toString(),
    val tag: String = "",
    val enabled: Boolean = true,
    val type: DnsServerType = DnsServerType.UDP,
    val address: String = "",            // 如 223.5.5.5 / tls://8.8.8.8 / https://dns.google/dns-query
    val addressResolver: String? = null, // 解析 address 所用的 DNS（可选）
    val detour: String? = null,          // 出口（可选，可为空 = 无需指定出口）
    /** ECS（EDNS Client Subnet）：CIDR（如 1.0.1.0/24）或 "auto"；空 = 不启用 */
    val clientSubnet: String? = null,
    /** ECH（Encrypted Client Hello），仅 tls/https/quic/h3 有效 */
    val echEnabled: Boolean = false,
    /** ECH 配置（PEM/echconfiglist），可选；留空表示仅启用 ECH 自动获取 */
    val echConfig: String? = null,
    /** fakeIP 自定义 IPv4 段（仅 fakeip 类型）；空 = 默认 10.0.0.0/8（避开 TEST-NET-2 冲突） */
    val inet4Range: String = "",
    /** fakeIP 自定义 IPv6 段（仅 fakeip 类型）；空 = 内核默认 fc00::/18 */
    val inet6Range: String = "",
)

@Serializable
enum class DnsServerType(val wireName: String) {
    UDP("udp"),
    TCP("tcp"),
    TLS("tls"),
    HTTPS("https"),
    QUIC("quic"),
    H3("h3"),
    LOCAL("local"),
    HOSTS("hosts"),
    FAKEIP("fakeip"),
}

/** 静态 hosts 映射条目（host → IP 列表），供 hosts 类型 DNS 服务器使用 */
@Serializable
data class HostsEntry(
    val id: String = UUID.randomUUID().toString(),
    val host: String = "",
    val ips: List<String> = emptyList(),
)

/** 配置 Profile（多配置快照） */
@Serializable
data class ConfigProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** snapshot 为 AppState JSON 快照，导入时 replaceAll 还原 */
    val snapshot: String = "",
)

/** DNS group：一组 DNS server 的命名集合（无需指定出口） */
@Serializable
data class DnsGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val serverTags: List<String> = emptyList(),
)

/**
 * 显式 DNS 规则（可手动创建，也由路由规则自动生成）。
 * 自动生成的规则 `autoFromRouteRuleId != null`，在 UI 中只读展示。
 */
@Serializable
data class DnsRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    /** 由哪条路由规则自动生成；null = 用户手动创建 */
    val autoFromRouteRuleId: String? = null,
    /** 手动规则的名称（仅展示用） */
    val name: String = "",

    // 匹配条件（一行一条）
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val domainKeywords: List<String> = emptyList(),
    val domainRegexes: List<String> = emptyList(),
    /** 内置 geosite 分类（如 cn / geolocation-!cn）；内核内置，无需远程下载 */
    val geosites: List<String> = emptyList(),
    val ruleSetTags: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    /** 内置 geoip 分类（如 cn / private）；内核内置，无需远程下载 */
    val geoips: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val ports: List<Int> = emptyList(),
    /** 查询类型：A / AAAA / CNAME / ... */
    val queryTypes: List<String> = emptyList(),

    // 动作
    /** 目标 DNS server tag（或 DNS group 名，生成时解析为首个成员） */
    val server: String = "",
    /** prefer_ipv4 / prefer_ipv6 / ipv4_only / ipv6_only / "" */
    val ipStrategy: String = "",
    val disableCache: Boolean = false,
    val rewriteTtl: Int? = null,
    /** 本条规则级别的 ECS 覆盖 */
    val clientSubnet: String? = null,
    /** 移除查询里的 ECS 记录并抑制 dns.client_subnet（与 clientSubnet 互斥） */
    val removeClientSubnet: Boolean = false,
    /** 规则动作：route=路由到 server / route-options=改写应答 / reject=拒绝 / pre-defined=预定义 */
    val action: String = "route",
    /** reject 方式的 rcode（route-options / reject 时用）：success / refused / formerror / notimp / nxdomain */
    val rcode: String = "",
    /** route-options：覆盖应答 A 记录（一行一个 IPv4） */
    val answers: List<String> = emptyList(),
    /** route-options：覆盖应答 NS 记录 */
    val ns: List<String> = emptyList(),
    /** route-options：覆盖应答 EXTRA 记录 */
    val extra: List<String> = emptyList(),
    /** 查询超时（sing-box duration，如 "4s"） */
    val timeout: String = "",
)

// ---------------------------------------------------------------------------
// Load balance（延迟优选 + "自动"模式）
// ---------------------------------------------------------------------------

@Serializable
enum class LoadBalanceMode(val displayName: String) {
    /** 延迟优选（urltest，tolerance=0） */
    LATENCY("延迟优选"),

    /** 均衡负载（urltest + tolerance，节点间分摊，不轻易切换） */
    BALANCED("均衡负载"),

    /** 手动切换（selector） */
    MANUAL("手动切换"),
}

/** urltest 选点模式（sb-AI UrltestMode） */
@Serializable
enum class UrltestMode(val wire: String, val displayName: String) {
    /** 上游行为：始终选 delay 最低的一个节点 */
    LEAST_TEST("least_test", "最低延迟"),

    /** fork 扩展：在 pool 大小的节点池内轮询分摊 */
    ROUND_ROBIN("round_robin", "轮询分摊"),
}

/** 粘性会话 key（round_robin 的 balancer.sticky_hash） */
@Serializable
enum class StickyHashKey(val wire: String, val displayName: String) {
    NONE("none", "无（纯轮询）"),
    PROCESS("process", "按进程"),
    DOMAIN("domain", "按域名"),
    SOURCE_IP("source_ip", "按源 IP"),
}

@Serializable
data class LoadBalanceConfig(
    val enabled: Boolean = false,
    val mode: LoadBalanceMode = LoadBalanceMode.BALANCED,

    /** “自动”模式：在负载均衡组之上自动选择当前最优出口，可与 LB 模式搭配 */
    val autoEnabled: Boolean = true,

    val checkUrl: String = "https://cp.cloudflare.com/generate_204",
    /** 测速间隔，sing-box duration 字符串（如 "15m"） */
    val interval: String = "15m",
    val toleranceMs: Int = 50,
    /** duration 字符串（如 "30m"） */
    val idleTimeout: String = "30m",
    val interruptExistConnections: Boolean = false,

    /** urltest 选点模式（sb-AI §208） */
    val urltestMode: UrltestMode = UrltestMode.LEAST_TEST,

    /** 仅使用 N 个节点参与负载均衡（balancer.pool）；round_robin 时生效 */
    val pool: Int = 3,
    /** balancer.pool_tolerance：0 = 保持池内节点存活；>0 = 按 delay 选最优 N 个 */
    val poolTolerance: Int = 0,
    /** balancer.sticky_hash：粘性会话 key */
    val stickyHash: List<StickyHashKey> = listOf(StickyHashKey.NONE),

    /** 参与负载均衡的节点 tag；空 = 全部代理节点 */
    val outbounds: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Proxy nodes（订阅解析或手动 JSON）
// ---------------------------------------------------------------------------

@Immutable
@Serializable
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    /** sing-box outbound 对象的原始 JSON（必须含 type / tag） */
    val outboundJson: String = "",
    /** 来源订阅 id；手动添加为 null */
    val subscriptionId: String? = null,
    /** urltest 测速结果（ms，0=未测，-1=失败，>0=延迟值） */
    val urlTestDelay: Int = 0,
    /** 最近一次测速时间戳（ms） */
    val urlTestTime: Long = 0L,
    /** 内核拒绝自动禁用的原因（null=未被内核拒绝禁用；禁用时记录内核原文） */
    val disabledReason: String? = null,
)

/** 订阅源：拉取分享链接并解析为节点 */
@Immutable
@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    /** 自动更新间隔（小时）；0 = 不自动 */
    val updateIntervalHours: Int = 24,
    /** 自定义 User-Agent（部分机场需要特定 UA 才返回可用内容） */
    val userAgent: String? = null,
    val lastUpdatedAt: Long = 0L,
    val lastError: String? = null,
    val nodeCount: Int = 0,
    /** 节点名包含关键字才导入（空 = 不过滤） */
    val includeKeyword: String = "",
    /** 节点名包含关键字则排除（空 = 不过滤） */
    val excludeKeyword: String = "",
    /** 订阅流量信息（来自 subscription-userinfo 响应头） */
    val trafficUpload: Long = 0L,
    val trafficDownload: Long = 0L,
    val trafficTotal: Long = 0L,
    val trafficExpire: Long = 0L,
    // ---- 节点后处理选项 ----
    /** 去重（去除重复配置） */
    val removeDuplicates: Boolean = true,
    /** 去除不安全节点（如无加密的 ss / 无 tls 的 trojan） */
    val removeInsecure: Boolean = false,
    /** 去除信息节点（机场把流量/到期/官网公告伪装成代理节点，名称含"剩余流量""套餐到期""官网"等） */
    val removeInfoNodes: Boolean = true,
    /** 保留不可达分组（urlTest 后仍有可用节点才移除） */
    val keepWorking: Boolean = false,
    /** 更新后自动测速（需内核运行；测速后可移除不可用节点/按延迟排序） */
    val urlTestAfterUpdate: Boolean = false,
    /** 移除不可用节点（仅 urlTestAfterUpdate 开启时生效） */
    val removeUnavailable: Boolean = false,
    /** 按延迟排序（仅 urlTestAfterUpdate 开启时生效） */
    val sortByLatency: Boolean = false,
    /** 订阅更新走哪个出口：direct（直连）/ proxy（代理）；空 = 跟随系统 */
    val detour: String = "direct",
    /** 跳过 TLS 证书校验（机场 CDN 域名证书不匹配时用；不安全，仅该订阅生效） */
    val skipCertVerify: Boolean = false,
    /** L7Filter：协议关键字过滤（空 = 不过滤；匹配 outboundJson 中的 type 字段） */
    val filterProtocol: String = "",
    /** L7Filter：地区关键字过滤（空 = 不过滤；匹配节点名称或 outbound server 字段） */
    val filterRegion: String = "",
)

/**
 * TUN 网络栈：UI 只暴露四种实现，其余栈（Go 原始名 / 其他实现）
 * 全部并入 tsbAI，借鉴其代码与架构来优化 tsbAI，不再作为独立选项出现。
 *
 * 注意 wireName 必须是内核实际注册的栈名。内核（sing-box 1.15 基线）只注册
 * system / gvisor / mixed / go；`tsbAI` 仅出现在内核的
 * `multi_queue is only supported by the go / tsbAI stack` 错误文案里，并非可配置的
 * stack 值——直接输出 "tsbAI" 会被内核以 `unknown stack: tsbAI` 拒绝，
 * 表现为 VPN 起不来（默认栈就是这个，等于全新安装必崩）。
 *
 * 因此 tsbAI 作为产品名保留，实际下发 "go"；GO 枚举只作为已写入配置的
 * 兼容别名存在（导入识别用），不进入 UI 选项。
 */
@Serializable
enum class TunStack(val displayName: String, val wireName: String) {
    /** tsbAI — 合并 Go 栈后的 sb-lxf TUN 栈（splice/GSO/multi_queue/oom-killer，默认首选） */
    TSB_AI("tsbAI", "go"),
    /** mixed：TCP 走 system、UDP 走 gVisor */
    MIXED("Mixed", "mixed"),
    /** 全系统栈：利用宿主内核 TCP，最低内存开销 */
    SYSTEM("System", "system"),
    /** 全 gVisor 用户态栈：最大兼容性，更高内存 */
    GVISOR("gVisor", "gvisor"),

    /** Go 栈原始名：与 TSB_AI 等价，仅供解析历史配置识别，UI 不展示 */
    @kotlinx.serialization.SerialName("go")
    GO("Go", "go"),
}

/** UI 里出现的 TUN 栈选项（GO 是历史兼容别名，不出现在选项里） */
val TUN_STACK_UI_OPTIONS = listOf(TunStack.TSB_AI, TunStack.MIXED, TunStack.SYSTEM, TunStack.GVISOR)

@Serializable
enum class LogLevel(val wireName: String) { TRACE("trace"), DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error"), FATAL("fatal"), PANIC("panic") }

/**
 * 分流模式（参考 Karing / Clash 的 规则-全局-直连）。
 *
 * 落到 sing-box 上就是 route.final 的取值：
 *  - RULES：留空，生成器自动选入口 tag（未命中规则走默认出口）
 *  - GLOBAL：固定为入口 tag，所有流量走代理
 *  - DIRECT：direct，所有流量直连
 */
@Serializable
enum class RouteMode(val displayName: String) {
    RULES("规则"),
    GLOBAL("全局"),
    DIRECT("直连"),
}

/** 分应用代理（include/exclude 名单） */
@Serializable
enum class PerAppProxyMode { OFF, INCLUDE, EXCLUDE }

@Serializable
data class PerAppProxy(
    val mode: PerAppProxyMode = PerAppProxyMode.OFF,
    val packages: List<String> = emptyList(),
)

/** 拆分隧道（Split Tunneling）：按域名走直连，其余走代理 */
@Serializable
data class SplitTunnel(
    val enabled: Boolean = false,
    /** 规则集 tag（如 "direct-domain"），需与路由规则 tag 对应 */
    val ruleTag: String = "split-direct",
    /** 直连域名列表（每行一个，支持通配符 *.example.com） */
    val domains: List<String> = emptyList(),
)

/** 主题模式（三大代理交集功能：外观设置） */
@Serializable
enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 配置覆盖的优先级模式 */
@Serializable
enum class OverridePriority(val displayName: String) {
    /** UI 层配置最高：导入的 JSON 只补充 UI 没有生成的部分 */
    UI_HIGHEST("UI 层最高"),

    /** 导入的 JSON 最高：覆盖 UI 生成的同名字段 */
    IMPORT_HIGHEST("导入 JSON 最高"),
}

/**
 * 配置覆盖：导入完整 sing-box JSON，与 UI 生成的配置深度合并。
 * 合并规则：对象递归合并；数组按 tag/name 去重合并（同 key 时高优先级方胜出），
 * 无 key 的数组（如 route.rules / dns.rules）按「高优先级在前、低优先级在后」拼接，
 * 因为规则数组按顺序匹配，高优先级规则必须先命中。
 */
@Serializable
data class ConfigOverride(
    val enabled: Boolean = false,
    val priority: OverridePriority = OverridePriority.UI_HIGHEST,
    val json: String = "",
)

/**
 * 资源条目：可配置 URL 自动更新的资源（China IP 列表、GeoIP、规则集等）
 * （China IP 列表、GeoIP 规则集、Hosts 等）。
 * 通过 VpnControlReceiver RESOURCE_UPDATE 触发批量更新。
 */
@Serializable
data class Resource(
    val id: String = UUID.randomUUID().toString(),
    /** 资源名称，如 "China IP List" / "GeoIP CN" */
    val name: String = "",
    /** 资源类型：china_ip / geoip / hosts / rule_set / custom */
    val resType: ResourceType = ResourceType.CUSTOM,
    /** 资源内容（持久化到本地文件/内存） */
    val content: String = "",
    /** 用于更新的 URL（空 = 仅手动维护） */
    val url: String = "",
    /** 最后更新时间戳 */
    val lastUpdatedAt: Long = 0L,
    /** 更新间隔（小时），默认 24h */
    val updateIntervalHours: Int = 24,
    /** 是否启用（enabled=false 不参与更新循环） */
    val enabled: Boolean = true,
    /** 最近一次更新错误信息 */
    val lastError: String? = null,
)

enum class ResourceType(val displayName: String) {
    CHINA_IP("China IP 列表"),

    // 以下类型已无 UI 入口（资源管理并入路由页后只保留 China IP），
    // 但枚举值不能删：已保存的配置里可能存着这些字符串，删掉会让
    // kotlinx.serialization 反序列化整份 AppState 失败并回退默认配置。
    @Deprecated("无 UI 入口，仅为兼容旧配置保留", ReplaceWith("CHINA_IP"))
    GEOIP("GeoIP"),
    @Deprecated("无 UI 入口，仅为兼容旧配置保留", ReplaceWith("CHINA_IP"))
    HOSTS("Hosts"),
    @Deprecated("无 UI 入口，仅为兼容旧配置保留", ReplaceWith("CHINA_IP"))
    RULE_SET("规则集"),
    @Deprecated("无 UI 入口，仅为兼容旧配置保留", ReplaceWith("CHINA_IP"))
    CUSTOM("自定义"),
}

@Serializable
data class AppSettings(
    val logLevel: LogLevel = LogLevel.WARN,
    val mtu: Int = 9000,
    val ipv6Route: Boolean = true,
    val autoDetectInterface: Boolean = true,
    val strictRoute: Boolean = true,
    /**
     * 系统 HTTP 代理下发（Android 10+）：开启后内核 tun 生成 platform.http_proxy，
     * 尊重系统代理的应用走该代理而不进隧道。适合「只代理忽略系统代理的应用」场景，
     * 无需把其余应用逐个加进 exclude_package。
     */
    val tunHttpProxyEnabled: Boolean = false,
    /** 系统 HTTP 代理监听端口（仅 tunHttpProxyEnabled 时生效） */
    val tunHttpProxyPort: Int = 2080,
    /** 系统 HTTP 代理旁路域名（这些域名不走该代理） */
    val tunHttpProxyBypassDomains: List<String> = emptyList(),
    val finalOutbound: String = "",   // 留空 = 自动（跟随入口 tag）
    /**
     * 分流模式（参考 Karing / Clash 的 规则-全局-直连）。
     * finalOutbound 是手动覆盖，非空时优先级高于本模式；
     * 本模式为 GLOBAL/DIRECT 时，生成器据此改写 route.final。
     */
    val routeMode: RouteMode = RouteMode.RULES,
    val dnsStrategy: String = "prefer_ipv4",   // prefer_ipv4 / prefer_ipv6 / ipv4_only / ipv6_only
    val perAppProxy: PerAppProxy = PerAppProxy(),
    // ---- 拆分隧道（已移除，用户反馈：写规则不好么？）----
    // val splitTunnel: SplitTunnel = SplitTunnel(),
    /** 配置覆盖：导入完整 JSON 与 UI 配置深度合并 */
    val configOverride: ConfigOverride = ConfigOverride(),
    /** 开机自动启动（需要 VPN 权限已授予） */
    val autoStartOnBoot: Boolean = false,
    /** 外观：主题模式 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 外观：Material You 动态取色（Android 12+） */
    val dynamicColor: Boolean = true,
    /** TUN IPv4 地址段 */
    val tunAddress: String = "172.18.0.1/30",
    /** TUN IPv6 地址段 */
    val tunAddress6: String = "fdfe:dcba:9876::1/126",

    // ---- 订阅身份（UA + HWID + device-meta） ----
    /** 全局订阅 User-Agent override；空 = 品牌 UA `sb-AI/<ver>` */
    val subscriptionUserAgent: String = "",
    /** 是否发送 x-hwid + device-meta 头（Remnawave 设备限制面板用；默认关） */
    val subscriptionSendHwid: Boolean = false,
    /** x-hwid（UUIDv4，懒生成，可被用户改写） */
    val subscriptionHwid: String = "",
    /** x-device-os override；空 = "android" */
    val subscriptionDeviceOs: String = "",
    /** x-ver-os override；空 = Build.VERSION.RELEASE */
    val subscriptionVerOs: String = "",
    /** x-device-model override；空 = Build.MODEL */
    val subscriptionDeviceModel: String = "",
    /** TUN 网络栈：tsbAI（sb-lxf 魔改） / mixed / system / gvisor / go（tsbAI=sb-lxf 专属低功耗Go栈） */
    val tunStack: TunStack = TunStack.TSB_AI,
    /** tsbAI/go 栈的 multi_queue（Linux 多队列网络引擎；仅 tsbAI/go 生效） */
    val tunMultiQueue: Boolean = false,
    // ---- TUN 空闲挂起（SPEC 020，内核需支持 lx.wg.idle_suspend；否则该配置为 no-op） ----
    /** 空闲挂起阈值（毫秒）：无流量超过该时长后挂起 TUN；0 = 关闭 */
    val idleSuspendDuration: Long = 0L,
    /** 挂起后拆除阈值（毫秒）：挂起态无流量超过该时长后拆除 TUN；0 = 不拆除 */
    val idleTeardownDuration: Long = 0L,

    // ---- DNS 高级功能（参考 FlClash/Bettbox/mihomo） ----
    /** DNS fallback 列表：当 nameserver 返回结果不满足 fallback-filter 时使用的备用 DNS */
    val dnsFallbackServers: List<String> = emptyList(),
    /**
     * GeoIP 污染判定：开启时把「启用的 China IP 资源内容」作为国内段白名单，
     * 解析结果命中则不切 fallback。sing-box 没有 Clash 的 geoip 字段，这是等价实现。
     */
    val dnsFallbackGeoip: Boolean = true,
    /** fallback-filter：命中这些 geosite 时只用 fallback（如 gfw） */
    val dnsFallbackGeosite: List<String> = emptyList(),
    /** fallback-filter：命中这些 IP 段时认为污染（如 240.0.0.0/4） */
    val dnsFallbackIpcidr: List<String> = emptyList(),
    /** fallback-filter：命中这些域名时认为污染（如 +.google.com） */
    val dnsFallbackDomain: List<String> = emptyList(),

    /** fakeIP 排除域名列表（这些域名不走 fakeIP，直接返回真实 IP） */
    val dnsFakeipFilter: List<String> = emptyList(),
    /** fakeIP 排除正则模式（参考 FlClash fake-ip-filter-pattern；匹配时不走 fakeIP） */
    val dnsFakeipFilterPatterns: List<String> = emptyList(),
    /** fakeIP 排除模式：blacklist=排除这些，whitelist=只对这些用 fakeIP */
    val dnsFakeipFilterMode: String = "blacklist",
    /** nameserver-policy：按域名/规则指定特定 DNS 服务器（格式：key=value，每行一条） */
    val dnsNameserverPolicy: List<String> = emptyList(),

    /** DNS 缓存容量（LRU，<1024 被内核忽略）；0 = 用内核默认 */
    val dnsCacheCapacity: Int = 0,
    /** 禁用 DNS 缓存 */
    val dnsDisableCache: Boolean = false,
    /** 禁用 DNS 缓存过期（与 optimistic 互斥） */
    val dnsDisableExpire: Boolean = false,
    /** 乐观缓存：过期条目先返回旧结果同时后台刷新；与 disable_cache/disable_expire 互斥 */
    val dnsOptimistic: Boolean = false,
    /** 乐观缓存最大服务窗口（duration 字符串，如 "3d"）；空 = 内核默认 3d */
    val dnsOptimisticTimeout: String = "",
    /** 单条 DNS 查询超时（duration 字符串，如 "10s"）；空 = 内核默认 10s */
    val dnsQueryTimeout: String = "",
    /** 反向映射：记录 IP→域名，供路由按域名匹配（1.9+） */
    val dnsReverseMapping: Boolean = false,
    /** resolve 动作策略：对 TUN/mixed 入站连接先解析域名（参考 Throne）；空 = 不生成该规则 */
    val dnsResolveStrategy: String = "",

    // ---- DPI 硬化：TLS 分片（参考 LxBox 016）----
    /** TLS 分片：把 ClientHello 拆成小 TCP 段（tls.fragment） */
    val tlsFragment: Boolean = false,
    /** TLS 记录分片：把握手拆成多个 TLS record（tls.record_fragment，推荐先试） */
    val tlsRecordFragment: Boolean = false,
    /** 分片回退延迟（tls.fragment_fallback_delay，duration 字符串） */
    val tlsFragmentFallbackDelay: String = "500ms",

    // ---- 资源管理：自定义 URL 列表（IP 列表、规则集等） ----
    /** 资源列表：可配置 URL 自动更新（如 China IP 列表、GeoIP 规则集等） */
    val resources: List<Resource> = emptyList(),
)

// ---------------------------------------------------------------------------
// Aggregate persisted state
// ---------------------------------------------------------------------------

@Immutable
@Serializable
data class AppState(
    val routeRules: List<RouteRule> = emptyList(),
    val routeRuleSets: List<RouteRuleSet> = emptyList(),
    val dnsServers: List<DnsServer> = emptyList(),
    val dnsGroups: List<DnsGroup> = emptyList(),
    /** 用户手动创建的 DNS 规则（自动生成的不入库，由生成器实时推导） */
    val dnsRules: List<DnsRule> = emptyList(),
    val loadBalance: LoadBalanceConfig = LoadBalanceConfig(),
    val proxyNodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    /** 静态 hosts 映射（hosts 类型 DNS 服务器使用，单独编辑，不在 DNS 服务器创建里） */
    val customHosts: List<HostsEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    /** 配置快照列表（多配置基准） */
    val profiles: List<ConfigProfile> = emptyList(),
    /** 当前激活的 profile id（空 = 无快照模式） */
    val activeProfileId: String = "",
)
