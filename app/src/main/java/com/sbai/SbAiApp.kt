package com.sbai

import android.app.Application
import com.sbai.data.RuleStore

class SbAiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预热规则仓库（读取 SharedPreferences 到内存）
        RuleStore.get(this)
    }
}
