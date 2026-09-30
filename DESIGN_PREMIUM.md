# Redesign premium escuro — CloudStream Kythour

## Objetivo

Modernizar a interface geral com uma aparência premium, escura e discreta, sem alterar IDs, listeners, modelos ou fluxos funcionais do CloudStream.

## Sistema visual

- Fundo principal: `#0B0D12`
- Superfície de navegação: `#11151D`
- Superfície elevada: `#151922`
- Controles secundários: `#1D2330`
- Destaque violeta: `#7C5CFC`
- Destaque secundário: `#8B72FF`
- Texto principal: `#F4F6FA`
- Texto secundário: `#A7AFBE`
- Ícones: `#BCC4D2`

## Alterações realizadas

- Paleta escura em camadas, com melhor hierarquia entre fundo, navegação e cards.
- Barra de status e navegação alinhadas ao fundo principal.
- Indicador violeta discreto na navegação inferior.
- Raios dos pôsteres aumentados para 14 dp e botões para 10 dp.
- Estilo de título exclusivo para seções da Home, sem afetar player e configurações.
- Espaçamento horizontal das seções e carrosséis aumentado.
- Perfil da Home ampliado mantendo área de toque de 50 dp.
- Cards com elevação reduzida e superfície escura para evitar halos agressivos.
- Gradiente dos títulos ampliado e legibilidade dos textos dos pôsteres melhorada.
- Barra de progresso da busca usa a cor primária.

## Compatibilidade preservada

- IDs e tipos de views existentes foram mantidos.
- Layouts de player, listeners e navegação não foram reestruturados.
- Temas Light, AMOLED, Dracula, Silent Blue, Lavender e Monet continuam baseados nos atributos existentes.

## Plugins Kythour pré-carregados

O APK contém 11 extensões em `assets/kythour/plugins` e o manifesto correspondente. Na primeira execução, `KythourBootstrap`:

- registra automaticamente o Kythour Repository;
- valida o SHA-256 de cada extensão empacotada;
- instala as extensões no armazenamento online padrão do CloudStream;
- permite o primeiro carregamento mesmo sem rede;
- preserva extensões atualizadas e nunca substitui uma versão mais nova por uma interna antiga.

Extensões: AnimeFire, AnimesOrion, CineGato, CineVision, NetCine, Pobreflix, RedeCanais, SuperCine, Tomato, TopAnimes e VeloPlayTV.

## Atualizações automáticas

- Plugins: o mecanismo padrão compara as versões locais com `builds/plugins.json` e baixa versões superiores com validação de hash.
- Aplicativo: consulta `app-update.json` no repositório Kythour ao abrir o app.
- Quando há `versionCode` superior, o APK é baixado pelo serviço foreground com progresso.
- A instalação sempre exige a confirmação apresentada pelo instalador de pacotes do Android; não há instalação silenciosa.
- O APK permanente fica publicado em uma release do GitHub; links tmpfiles.org são apenas temporários.

## Build confiável em pouca memória

A VPS possui cerca de 4 GB de RAM. O Gradle usa um único worker, compilador Kotlin no mesmo processo e heap controlado. O script `build-low-memory.sh` interrompe temporariamente o Redroid, encerra daemons antigos, executa o build e reinicia o Redroid mesmo se houver falha.

## Validação

- `:app:assemblePrereleaseDebug`: `BUILD SUCCESSFUL in 8m 39s`.
- APK: `app/build/outputs/apk/prerelease/debug/app-prerelease-debug.apk`.
- Tamanho: `92.162.624` bytes.
- SHA-256: `be8f671f5031e63d2d88b1d88361b18dab7f79149f94018539051edfa1e57345`.
- Pacote: `com.lagradost.cloudstream3.prerelease.debug`.
- Versão: `4.8.0-PRE`, versionCode `29844840`.
- Instalação limpa no Redroid Android 11: aprovada.
- Primeiro início: as 11 extensões foram instaladas e carregadas com sucesso.
- Segundo início: as 11 extensões foram preservadas (`installed=false`), confirmando idempotência.
- Logcat: nenhuma `FATAL EXCEPTION` do aplicativo.

## Observação do ambiente de teste

O APK debug pode coexistir com o prerelease normal. Seus dados permanecem separados dos dados da variante prerelease.
