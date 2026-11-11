# SonicFlow

Aplicativo Android para acompanhar e controlar o headphone **QCY H3**. O projeto usa Kotlin, Jetpack Compose e Material 3, com cores dinâmicas do Material You no Android 12 ou posterior.

> O desenvolvimento foi validado por compilação. Os comandos Bluetooth dependem do firmware e precisam ser conferidos com o headphone conectado; não há teste automatizado de hardware neste repositório.

## Recursos

- Conexão e reconexão Bluetooth LE com o QCY H3.
- Estado da conexão e nível de bateria anunciado pelo headphone.
- Controles de ANC desligado, cancelamento, cenas/intensidade ANC e transparência.
- Modo Gamer de baixa latência com controle pelo protocolo JieLi RCSP.
- Ativação opcional do Modo Gamer ao abrir jogos; o Android exige que o usuário conceda **Acesso aos dados de uso**.
- Equalizador do áudio do Android com perfis de som. Ele atua no áudio do telefone e não grava curvas no DSP interno do H3.
- Pop-up de conexão compacto e cartão maior na tela inicial, quando as permissões necessárias estão disponíveis.
- Seleção de idioma e tema Material You; a cobertura das traduções varia entre telas e mensagens.
- Tela de permissões, informações do autor e links externos.

## Compatibilidade e requisitos

- Android 8.0 (API 26) ou posterior.
- JDK 17.
- Android SDK Platform 35 para compilar.
- Gradle Wrapper 8.14.3 incluído no repositório.

## Abrir e compilar

Abra o projeto no Android Studio e aguarde a sincronização do Gradle. Também é possível compilar pelo terminal:

```powershell
.\gradlew.bat assembleDebug
```

No macOS/Linux:

```bash
./gradlew assembleDebug
```

O APK de depuração é criado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para instalar com ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK debug é assinado com a chave de depuração local. Para distribuição de produção, configure uma chave de release privada fora do repositório e use uma variante de release assinada.

## Permissões do Android

O SonicFlow pode solicitar ou encaminhar para as configurações do Android:

- **Dispositivos próximos/Bluetooth:** localizar o canal BLE e controlar o headphone.
- **Notificações:** exibir notificações do serviço em versões compatíveis.
- **Sobrepor a outros apps:** mostrar os cartões de conexão flutuantes.
- **Acesso aos dados de uso:** detectar jogos em primeiro plano para a automação opcional.
- **Áudio:** conectar o equalizador ao áudio reproduzido pelo telefone.

Sem a permissão de sobreposição, o app usa uma notificação do sistema como alternativa. Os fabricantes também podem restringir serviços em segundo plano por otimização de bateria.

## Estrutura do projeto

```text
app/src/main/java/com/sonicflow/
├── MainActivity.kt             # Interface Compose, abas, permissões e equalizador
├── QcyBleController.kt         # Descoberta BLE, GATT, RCSP, bateria, ANC e modo gamer
├── ConnectionPopupService.kt   # Serviço Bluetooth e cartões/notificações de conexão
├── AndroidEqualizer.kt         # Integração com o equalizador do Android
└── SonicLocale.kt              # Idiomas e traduções da interface
```

## Bluetooth e referências

O controlador usa características BLE e frames JieLi RCSP para comunicar-se com o QCY H3. O protocolo/lista de capacidades foi estudado a partir da documentação e dos modelos do projeto [PairFree](https://github.com/Omega-Destroyer/PairFree). O SonicFlow não incorpora o código-fonte do PairFree.

Consulte também o [guia oficial do QCY H3](https://www.qcy.com/products/qcy-h3-wireless-headphone) e a documentação Android de [system bars e edge-to-edge](https://developer.android.com/design/ui/mobile/guides/foundations/system-bars).

## Rede e dados

O app usa a internet para carregar a imagem oficial do headphone. Os links sociais abrem somente quando tocados. O acesso ao uso do dispositivo serve à detecção local de jogos. O código da tela “Sobre mim” contém os dados de apoio financeiro fornecidos pelo autor.

## Problemas conhecidos

- O controle Bluetooth deve ser testado com o QCY H3 pareado, conectado ao telefone e sem outro app QCY disputando o canal BLE.
- Os dados de bateria dependem do que o firmware anuncia; a leitura pode não aparecer imediatamente após a conexão.
- A latência real do Modo Gamer depende do headphone, firmware, codec e aparelho Android.
- O Android não permite que aplicativos comuns redimensionem o indicador de gestos; o SonicFlow usa barras transparentes, edge-to-edge e insets do sistema.

## Releases

Os APKs para instalação são publicados como assets na página [Releases](https://github.com/Erivaldogelson/Sonic-Flow/releases). Para baixar a versão de teste mais recente, abra a release marcada como mais recente e baixe o APK debug anexado.

## Créditos

- Projeto e interface: Erivaldo Gelson da Rocha João.
- Referência de protocolo Bluetooth: [PairFree](https://github.com/Omega-Destroyer/PairFree).
- Imagem do produto: [QCY](https://www.qcy.com/products/qcy-h3-wireless-headphone).

## Licença

Este repositório ainda não declara uma licença de código aberto. O código está visível publicamente, mas a reutilização e redistribuição continuam reservadas ao autor até que uma licença seja escolhida.
