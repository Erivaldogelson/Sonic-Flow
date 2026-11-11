package com.sonicflow

import android.content.Context

/** Shared by the foreground UI and connection pop-up service. */
object SonicLocale {
    data class Language(val id: String, val label: String, val locale: String?)
    val languages = listOf(
        Language("system", "System language", null),
        Language("pt-BR", "Portuguese (Brazil)", "pt-BR"),
        Language("pt-AO", "Portuguese (Angola)", "pt-AO"),
        Language("pt-CV", "Portuguese (Cape Verde)", "pt-CV"),
        Language("pt-MZ", "Portuguese (Mozambique)", "pt-MZ"),
        Language("kea", "Cape Verdean Creole", "kea"),
        Language("en", "English", "en"), Language("zh", "中文", "zh-CN"),
        Language("ko", "한국어", "ko"), Language("ja", "日本語", "ja"),
        Language("ru", "Русский", "ru"), Language("af", "Afrikaans", "af"),
        Language("kmb", "Kimbundu", "kmb")
    )
    fun selected(context: Context): String = context.getSharedPreferences("sonicflow", Context.MODE_PRIVATE).getString("app_language", "system") ?: "system"
    fun text(context: Context, source: String): String {
        val id = selected(context)
        if (id == "system" || id.startsWith("pt-")) return source
        val language = languages.firstOrNull { it.id == id }?.locale?.substringBefore('-') ?: "en"
        return additionalTranslations[language]?.get(source) ?: translations[language]?.get(source) ?: source
    }
    private val additionalTranslations = mapOf("en" to mapOf(
        "Sobre mim" to "About me", "Minhas redes sociais" to "My social media",
        "Ajude a investir neste e em novos projetos" to "Help fund this and future projects",
        "Toque nos dados para copiá-los." to "Tap a detail to copy it.",
        "PIX • Brasil" to "PIX • Brazil", "IBAN • Europa" to "IBAN • Europe",
        "SWIFT • Estados Unidos" to "SWIFT • United States", "Ajustar áudio" to "Modify audio settings",
        "Acesso à internet e serviço em segundo plano" to "Internet and background service access",
        "Desconectado" to "Disconnected"
    ))
    private val translations = mapOf(
        "en" to mapOf("Ajustes" to "Settings", "Sobre" to "About", "Nome no pop-up" to "Pop-up name", "Deixe vazio para usar o nome salvo no Bluetooth." to "Leave empty to use the Bluetooth name.", "Nome exibido" to "Display name", "Permissões" to "Permissions", "Idioma" to "Language", "Escolher idioma" to "Select language", "Idioma do sistema" to "System language", "Concedida" to "Allowed", "Não concedida" to "Not allowed", "Bluetooth e dispositivos próximos" to "Bluetooth and nearby devices", "Notificações" to "Notifications", "Sobre outros apps" to "Display over other apps", "Abrir configurações" to "Open settings", "SonicFlow" to "SonicFlow", "Meus fones" to "My headphones", "Som" to "Sound", "Fones" to "Headphones", "Conectar" to "Connect", "Reconectar" to "Reconnect", "Buscando" to "Searching", "Cancelamento de ruído" to "Noise cancellation", "Desligado" to "Off", "Transparência" to "Transparency", "Equalizador" to "Equalizer", "Ajuste o som" to "Tune your sound", "Pop-up" to "Pop-up", "Card Pop-up" to "Pop-up card", "Aviso ao trocar de dispositivo" to "Device switch alert", "Modo Gamer" to "Game mode", "Reduz a latência do áudio" to "Reduces audio latency", "Ativar automaticamente ao abrir um jogo" to "Turn on automatically when a game opens", "Pronto para conectar" to "Ready to connect", "Conecte o fone para enviar comandos ANC." to "Connect your headphones to send ANC commands.", "Os modos são enviados diretamente pelo Bluetooth LE." to "Modes are sent directly over Bluetooth LE.", "Sobre outros apps • parte inferior" to "Over other apps • bottom", "Toque para permitir sobreposição" to "Tap to allow overlay", "Áudio pausado neste celular" to "Audio paused on this phone", "Áudio retomado neste celular" to "Audio resumed on this phone"),
        "zh" to mapOf("Ajustes" to "设置", "Sobre" to "关于", "Idioma" to "语言", "Escolher idioma" to "选择语言", "Idioma do sistema" to "系统语言", "Permissões" to "权限", "Conectar" to "连接", "Reconectar" to "重新连接", "Cancelamento de ruído" to "降噪", "Desligado" to "关闭", "Transparência" to "通透模式", "Equalizador" to "均衡器", "Fones" to "耳机", "Som" to "声音", "Áudio pausado neste celular" to "音频已在此手机暂停", "Áudio retomado neste celular" to "音频已在此手机恢复"),
        "ko" to mapOf("Ajustes" to "설정", "Sobre" to "정보", "Idioma" to "언어", "Escolher idioma" to "언어 선택", "Idioma do sistema" to "시스템 언어", "Permissões" to "권한", "Conectar" to "연결", "Reconectar" to "다시 연결", "Cancelamento de ruído" to "노이즈 캔슬링", "Desligado" to "끄기", "Transparência" to "주변 소리", "Equalizador" to "이퀄라이저", "Fones" to "헤드폰", "Som" to "사운드", "Áudio pausado neste celular" to "이 휴대전화에서 오디오 일시중지", "Áudio retomado neste celular" to "이 휴대전화에서 오디오 재개"),
        "ja" to mapOf("Ajustes" to "設定", "Sobre" to "アプリ情報", "Idioma" to "言語", "Escolher idioma" to "言語を選択", "Idioma do sistema" to "システム言語", "Permissões" to "権限", "Conectar" to "接続", "Reconectar" to "再接続", "Cancelamento de ruído" to "ノイズキャンセリング", "Desligado" to "オフ", "Transparência" to "外音取り込み", "Equalizador" to "イコライザー", "Fones" to "ヘッドホン", "Som" to "サウンド"),
        "ru" to mapOf("Ajustes" to "Настройки", "Sobre" to "О приложении", "Idioma" to "Язык", "Escolher idioma" to "Выбрать язык", "Idioma do sistema" to "Язык системы", "Permissões" to "Разрешения", "Conectar" to "Подключить", "Reconectar" to "Переподключить", "Cancelamento de ruído" to "Шумоподавление", "Desligado" to "Выкл.", "Transparência" to "Прозрачность", "Equalizador" to "Эквалайзер", "Fones" to "Наушники", "Som" to "Звук"),
        "af" to mapOf("Ajustes" to "Instellings", "Sobre" to "Oor", "Idioma" to "Taal", "Escolher idioma" to "Kies taal", "Idioma do sistema" to "Stelseltaal", "Permissões" to "Toestemmings", "Conectar" to "Koppel", "Reconectar" to "Herkoppel", "Cancelamento de ruído" to "Geraaskansellasie", "Desligado" to "Af", "Transparência" to "Deursigtigheid", "Equalizador" to "Gelykmaker", "Fones" to "Oorfone", "Som" to "Klank"),
        "kea" to mapOf("Ajustes" to "Konfigurason", "Sobre" to "Sobri", "Idioma" to "Língua", "Escolher idioma" to "Skodje língua", "Idioma do sistema" to "Língua di sistema", "Permissões" to "Permison", "Conectar" to "Liga", "Reconectar" to "Liga ôtu bês", "Cancelamento de ruído" to "Kanselamentu di barulhu", "Desligado" to "Désliga", "Transparência" to "Transparénsia", "Equalizador" to "Ikualizador", "Fones" to "Fones", "Som" to "Som"),
        "kmb" to mapOf("Ajustes" to "Mavulu", "Sobre" to "Mu", "Idioma" to "Kibekelo", "Escolher idioma" to "Sola kibekelo", "Idioma do sistema" to "Kibekelo kya sistema", "Permissões" to "Kusoneka", "Conectar" to "Kukwata", "Reconectar" to "Kukwata kasyena", "Cancelamento de ruído" to "Kusukula kwavulu", "Desligado" to "Kufwisa", "Transparência" to "Kumona mu kilu", "Equalizador" to "Kusolola som", "Fones" to "Mafoni", "Som" to "Som")
    )
}
