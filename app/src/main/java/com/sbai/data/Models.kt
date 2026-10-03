package com.sbai.data

import kotlinx.serialization.Serializable
import java.util.UUID

// ---------------------------------------------------------------------------
// Route rules
// ---------------------------------------------------------------------------

@Serializable
enum class RuleAction(val outboundTag: String?) {
    PROXY(null),      // resolved at build time to the proxy entry outbound
    DIRECT("direct"),
    BLOCK("block"),
}

/** 逻辑运算：单条件 / AND / OR */
@Serializable
enum class RuleLogic(val wireName: String?) {
    SINGLE(null),
    AND("and"),
    OR("or"),
}

@Serializable
data class RouteRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val action: RuleAction = RuleAction.PROXY,

    // 一行一条：域名 / 域名后缀 / IP / IP CIDR / 远程规则集 tag
    val domains: List<String> = emptyList(),          // 精确域名
    val domainSuffixes: List<String> = emptyList(),   // 域名后缀
    val domainKeywords: List<String> = emptyList(),   // 域名关键词
    val domainRegexes: List<String> = emptyList(),    // 域名正则
    val ipCidrs: List<String> = emptyList(),          // IP / CIDR（IP 规则）
    val ruleSetTags: List<String> = emptyList(),      // 引用的规则集（含远程 IP 规则集）

    // network / protocol 允许多选
    val networks: List<String> = emptyList(),         // tcp / udp
    val protocols: List<String> = emptyList(),        // http / tls / quic / dns / bittorrent / stun ...

    val ports: List<Int> = emptyList(),
    val portRanges: List<String> = emptyList(),       // "8000:9000"

    val logic: RuleLogic = RuleLogic.SINGLE,
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
                domainKeywords.isNotEmpty() || domainRegexes.isNotEmpty()
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

/** DNS group：一组 DNS server 的命名集合（无需指定出口） */
@Serializable
data class DnsGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val serverTags: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Load balance（参考 LxBox 的负载均衡 + “自动”模式）
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

@Serializable
data class LoadBalanceConfig(
    val enabled: Boolean = false,
    val mode: LoadBalanceMode = LoadBalanceMode.BALANCED,

    /** “自动”模式：在负载均衡组之上自动选择当前最优出口，可与 LB 模式搭配 */
    val autoEnabled: Boolean = true,

    val checkUrl: String = "https://www.gstatic.com/generate_204",
    val intervalSeconds: Int = 300,
    val toleranceMs: Int = 50,
    val idleTimeoutSeconds: Int = 1800,
    val interruptExistConnections: Boolean = false,

    /** 参与负载均衡的节点 tag；空 = 全部代理节点 */
    val outbounds: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Proxy nodes（订阅解析或手动 JSON）
// ---------------------------------------------------------------------------

@Serializable
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    /** sing-box outbound 对象的原始 JSON（必须含 type / tag） */
    val outboundJson: String = "",
    /** 来源订阅 id；手动添加为 null */
    val subscriptionId: String? = null,
)

/** 订阅源：拉取分享链接并解析为节点 */
@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val lastUpdatedAt: Long = 0L,
    val lastError: String? = null,
    val nodeCount: Int = 0,
)

// ---------------------------------------------------------------------------
// App settings
// ---------------------------------------------------------------------------

@Serializable
enum class LogLevel(val wireName: String) { TRACE("trace"), DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error"), FATAL("fatal"), PANIC("panic") }

@Serializable
data class AppSettings(
    val logLevel: LogLevel = LogLevel.WARN,
    val mtu: Int = 9000,
    val ipv6Route: Boolean = true,
    val autoDetectInterface: Boolean = true,
    val strictRoute: Boolean = true,
    val finalOutbound: String = "",   // 留空 = 自动（跟随入口 tag）
    val dnsStrategy: String = "prefer_ipv4",   // prefer_ipv4 / prefer_ipv6 / ipv4_only / ipv6_only
)

// ---------------------------------------------------------------------------
// Aggregate persisted state
// ---------------------------------------------------------------------------

@Serializable
data class AppState(
    val routeRules: List<RouteRule> = emptyList(),
    val routeRuleSets: List<RouteRuleSet> = emptyList(),
    val dnsServers: List<DnsServer> = emptyList(),
    val dnsGroups: List<DnsGroup> = emptyList(),
    val loadBalance: LoadBalanceConfig = LoadBalanceConfig(),
    val proxyNodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
)
