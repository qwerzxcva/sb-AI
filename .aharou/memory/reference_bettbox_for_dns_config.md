---
name: reference_bettbox_for_dns_config
description: 实现或修改 DNS、fakeIP、fallback 等网络配置功能时阅读
---
- 用户明确要求：本项目的 DNS、fakeIP、fallback 等功能，需要参考并借鉴 bettbox 的实现。
- 动手前先查看 bettbox 中对应模块的设计，包括配置项、交互方式和默认值，再在本项目中实现。
- 已知待办：本地节点列表展开后为空，需要增加“添加节点”功能。
- 用户提到 bettbox 和 FlClash 最近新增了一种网络栈（此前只有 system、gVisor、mixed）。具体名称和实现尚未确认，涉及栈选项时需要先核实。