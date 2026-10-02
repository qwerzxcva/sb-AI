package com.sbai

import android.app.Application
import com.sbai.models.RouteRuleManager
import com.sbai.models.DnsRuleManager
import com.sbai.models.LoadBalanceManager

class SbAiApp : Application() {
    lateinit var routeRuleManager: RouteRuleManager
    lateinit var dnsRuleManager: DnsRuleManager
    lateinit var loadBalanceManager: LoadBalanceManager

    override fun onCreate() {
        super.onCreate()
        routeRuleManager = RouteRuleManager(this)
        dnsRuleManager = DnsRuleManager(this)
        loadBalanceManager = LoadBalanceManager(this)
    }
}
