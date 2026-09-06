# AuthSystem — Plugin de Login/Registro para Spigot 26.2

Sistema completo de autenticação para servidores Minecraft **offline-mode/cracked**, com suporte a contas **Premium (Mojang)** e contas locais com senha.

> **Importante:** o nick não pertence exclusivamente à Mojang. Se já existir uma conta local registrada para um nick, essa conta **sempre tem prioridade**, mesmo que o nick também seja usado por uma conta Premium.

## ✨ Funcionalidades

### 🔐 Autenticação por senha

- `/login <senha>` para contas já registradas.
- `/registro <senha> <confirmar-senha>` para criar uma conta local.
- Senhas armazenadas com **PBKDF2-HMAC-SHA256**.
- **600.000 iterações** para novos hashes.
- Salt aleatório de **16 bytes** por senha.
- Chave derivada de **256 bits**.
- Compatibilidade com hashes antigos de **65.536 iterações**, com atualização automática após login bem-sucedido.
- Validação configurável do tamanho da senha.
- Apenas letras e números são aceitos por padrão.
- O processamento pesado de PBKDF2 é executado de forma **assíncrona**, evitando bloquear a thread principal do Bukkit.
- Proteção contra múltiplas tentativas simultâneas de login/registro da mesma sessão.

### 👑 Verificação Premium

Contas Premium são verificadas por um handshake criptográfico, e não apenas pelo nick.

Fluxo:

1. O plugin intercepta o `LOGIN_START`.
2. É criado um desafio com chave pública **RSA 2048 bits** e token aleatório.
3. O cliente responde com o token e a chave secreta protegidos por RSA.
4. O plugin valida o token e habilita **AES/CFB8** na conexão.
5. É calculado o `serverId/hash` da sessão.
6. O plugin consulta o Session Server da Mojang (`hasJoined`).
7. A resposta é validada e a prova Premium fica vinculada ao UUID e IP da conexão.
8. Quando o jogador entra, a prova é consumida e a sessão pode ser autenticada automaticamente.
9. Se a Mojang não confirmar a sessão, o jogador segue como cracked e precisa usar senha.

Proteções adicionais da verificação:

- Timeout lógico de **8 segundos** para a consulta/verificação.
- Timeout HTTP de **4 segundos** para conexão e leitura.
- Respostas HTTP limitadas a **16 KB**.
- Estado de handshake protegido contra reservas duplicadas.
- Verificações pendentes possuem limpeza automática em caso de falha/timeout.
- Limite local de consultas à Mojang por IP configurável.

### 🧠 Regra principal: conta local tem prioridade

O comportamento intencional do servidor é:

```text
Nick sem conta local
        │
        ├── Premium confirmado → login automático
        │
        └── Premium não confirmado → /login ou /registro

Nick com conta local
        │
        └── SEMPRE pede a senha da conta local
```

Isso significa que:

- Um jogador cracked pode registrar um nick que também pertence a uma conta Premium.
- O dono Premium desse nick não pode substituir a conta local.
- O dono Premium não pode criar uma nova senha para esse nick enquanto existir a conta local.
- O UUID/data local não é substituído automaticamente pela conta Premium.
- A conta Premium só recebe login automático quando **não existe conta local registrada**.

Essa regra é necessária para manter o funcionamento de servidores offline-mode.

## 🛡️ Sistema anti-bypass

O plugin possui várias camadas de proteção enquanto o jogador ainda não está autenticado.

### AuthListener

Enquanto não autenticado, o jogador fica limitado até concluir `/login`, `/registro` ou uma autenticação Premium válida.

Proteções incluem:

- Bloqueio de movimento.
- Bloqueio de chat.
- Bloqueio de comandos não autorizados.
- Bloqueio de interação com o mundo.
- Bloqueio de quebra/colocação de blocos.
- Bloqueio de dano recebido.
- Proteção do estado de autenticação.
- Timeout para jogadores que não realizam login.
- Liberação do jogador somente após autenticação válida.

### AntiBypassListener

Bloqueia formas indiretas de contornar o congelamento:

- Teleportes não relacionados a plugins.
- Abertura de inventários/baús.
- Clique e arraste em inventários.
- Troca de item da mão.
- Entrada/saída de veículos.
- Entrada em cama.
- Interação com entidades.
- Lançamento de projéteis/itens.
- Pesca.
- Consumo de itens.
- Coleta de itens.
- Mobs mirando no jogador.
- Dano causado pelo jogador.

Teleportes causados por plugins continuam permitidos para não quebrar sistemas de lobby/spawn.

## 🚫 Proteção contra força bruta

O `LoginProtection` utiliza duas camadas de controle:

### Por IP

Erros de senha são registrados por endereço IP, impedindo que o atacante simplesmente desconecte e reconecte para zerar as tentativas.

### Por conta/nick

Os erros também são registrados pela conta, reduzindo a possibilidade de contornar a proteção utilizando vários IPs.

Configuração padrão:

```yaml
max-tentativas-login: 3
bloqueio-apos-exceder-tentativas-minutos: 5
```

Com `3` tentativas configuradas, os três primeiros erros são registrados e o excesso bloqueia a tentativa seguinte.

A proteção possui expiração das entradas antigas e é aplicada antes do processamento pesado de PBKDF2.

## 🌐 Limites de sessão e IP

O `SessionManager` controla as sessões autenticadas e mantém limites separados para:

- Quantidade de contas autenticadas simultaneamente pelo mesmo IP.
- Quantidade de IPs associados a uma conta.
- Contas cracked por dados locais.
- Contas Premium por UUID da Mojang.

Configuração:

```yaml
max-contas-por-ip: 1
max-ips-por-conta: 1
```

Valores:

- `1` = uma conta/IP.
- `2` = duas contas/IPs.
- `0` = ilimitado.

A reserva de IP é validada antes de substituir uma reserva anterior, evitando perda de sessão quando o novo IP ultrapassa o limite.

## 💾 Persistência dos dados

### PlayerDataManager

Responsável pelas contas locais e seus dados.

Inclui:

- Cadastro de contas.
- Armazenamento do hash e salt da senha.
- Iterações utilizadas no hash.
- IPs associados à conta.
- Leitura e gravação em YAML.
- Escritas assíncronas para reduzir impacto no servidor.
- Coalescência de várias gravações próximas.
- Snapshots consistentes dos dados antes da escrita.
- `ioLock` para serializar gravações no mesmo arquivo.
- Controle de versão para detectar alterações feitas durante uma gravação e realizar uma nova persistência.
- Salvamento síncrono no desligamento do plugin.

### PremiumAccountManager

Mantém os dados persistentes relacionados às contas Premium.

Inclui as mesmas proteções de persistência:

- Snapshot consistente.
- Escrita assíncrona.
- Coalescência de saves.
- Serialização de I/O com lock.
- Controle de versão para evitar que alterações concorrentes sejam perdidas durante uma escrita.
- Salvamento síncrono no desligamento.

## 🧩 Componentes principais

```text
AuthSystem
│
├── commands/
│   ├── LoginCommand.java
│   └── RegisterCommand.java
│
├── listeners/
│   ├── AuthListener.java
│   ├── AntiBypassListener.java
│   └── PremiumVerificationListener.java
│
├── manager/
│   ├── PlayerDataManager.java
│   ├── PremiumAccountManager.java
│   ├── SessionManager.java
│   ├── LoginProtection.java
│   ├── MessagesManager.java
│   └── MojangRateLimiter.java
│
└── util/
    ├── PasswordUtils.java
    ├── IpResolver.java
    ├── PremiumAuthenticator.java
    └── PremiumLoginVerifier.java
```

### LoginCommand

Responsável pelo `/login`.

- Valida estado da sessão.
- Verifica bloqueio por IP e conta.
- Impede operações simultâneas.
- Executa PBKDF2 fora da thread principal.
- Atualiza hashes antigos automaticamente.
- Registra falhas por IP e conta.
- Limpa as proteções após login válido.
- Valida limites de sessão/IP antes de autenticar.
- Trata exceções do processamento assíncrono sem deixar a sessão presa em estado de processamento.

### RegisterCommand

Responsável pelo `/registro`.

- Valida senha e confirmação.
- Verifica existência da conta.
- Impede registros simultâneos da mesma sessão.
- Gera salt e hash PBKDF2 de forma assíncrona.
- Persiste os dados da nova conta.
- Aplica os limites configurados para contas/IP.

### AuthListener

Coordena o ciclo de autenticação do jogador e aplica as restrições até o login.

### AntiBypassListener

Adiciona proteção contra ações indiretas que poderiam permitir interação antes da autenticação.

### PremiumVerificationListener

Controla o handshake de Premium no protocolo de login, incluindo token, criptografia, timeout, reservas de conexão e continuação do fluxo cracked quando a verificação não é confirmada.

### PremiumLoginVerifier

Executa a validação criptográfica e consulta à Mojang.

- RSA 2048.
- AES/CFB8.
- Token de verificação.
- Cálculo do `serverId/hash`.
- Consulta `hasJoined`.
- Timeout e tratamento de falhas.
- Limite de resposta HTTP.
- Controle de verificações pendentes.

### PremiumAuthenticator

Guarda temporariamente a prova Premium já validada para que ela possa ser consumida quando o jogador efetivamente entrar.

### SessionManager

Gerencia:

- Jogadores autenticados.
- Jogadores Premium.
- Timeout de login.
- IPs autenticados por conta.
- Limites de contas por IP.
- Limites de IPs por conta.
- Registro/remoção segura das reservas de sessão.

### LoginProtection

Controla tentativas de login incorretas por **IP e conta**, com expiração e bloqueio temporário.

### MojangRateLimiter

Limita as consultas ao Session Server da Mojang por endereço IP para reduzir abuso e consumo desnecessário do serviço externo.

### MessagesManager

Carrega os Titles de autenticação a partir de `mensagens/titulos.yml`, com suporte a códigos de cor `&`.

### PasswordUtils

Centraliza a segurança das senhas:

- PBKDF2-HMAC-SHA256.
- 600.000 iterações atuais.
- Salt aleatório.
- Hash de 256 bits.
- Verificação segura.
- Compatibilidade com hashes legados.
- Validação das regras da senha.

### IpResolver

Obtém o endereço IP real disponível na conexão Bukkit, com tratamento seguro para endereços nulos.

## ⚙️ Configuração

O `config.yml` atual possui:

```yaml
tempo-limite-login-segundos: 60
max-tentativas-login: 3
bloqueio-apos-exceder-tentativas-minutos: 5

minimo-caracteres-senha: 7
maximo-caracteres-senha: 16

max-contas-por-ip: 1
max-ips-por-conta: 1

max-verificacoes-mojang-por-minuto: 30
```

### Explicação

| Configuração | Padrão | Função |
|---|---:|---|
| `tempo-limite-login-segundos` | `60` | Tempo para concluir a autenticação |
| `max-tentativas-login` | `3` | Tentativas de senha permitidas antes do bloqueio |
| `bloqueio-apos-exceder-tentativas-minutos` | `5` | Duração do bloqueio |
| `minimo-caracteres-senha` | `7` | Tamanho mínimo da senha |
| `maximo-caracteres-senha` | `16` | Tamanho máximo da senha |
| `max-contas-por-ip` | `1` | Contas autenticadas simultaneamente por IP |
| `max-ips-por-conta` | `1` | IPs permitidos por conta |
| `max-verificacoes-mojang-por-minuto` | `30` | Consultas Mojang permitidas por IP/minuto |

## 🎮 Comandos

| Comando | Descrição |
|---|---|
| `/login <senha>` | Faz login em uma conta registrada |
| `/registro <senha> <confirmar-senha>` | Cria uma nova conta |
| `/register <senha> <confirmar-senha>` | Alias de `/registro` |
| `/cadastrar <senha> <confirmar-senha>` | Alias de `/registro` |

## 🔒 Segurança

O AuthSystem foi estruturado para evitar os principais vetores de abuso de um sistema de login offline-mode:

- Não confia somente no nick para identificar Premium.
- Não permite que uma verificação Premium substitua uma conta local existente.
- Não executa PBKDF2 pesado na thread principal.
- Possui proteção contra tentativas repetidas por IP e conta.
- Possui limite de consultas à Mojang.
- Utiliza RSA 2048 para o desafio Premium.
- Utiliza AES/CFB8 para a conexão criptografada.
- Limita o tamanho da resposta HTTP da Mojang.
- Possui timeouts para chamadas externas.
- Protege estados de handshake contra duplicação.
- Serializa gravações YAML e detecta alterações concorrentes.
- Controla sessões autenticadas e limites por IP/conta.
- Mantém o jogador bloqueado até que a autenticação seja concluída.

## 🏗️ Requisitos

- **Java 21**
- **Spigot API 26.2-R0.1-SNAPSHOT**
- **PacketEvents 2.13.0**
- Servidor Minecraft em `offline-mode` para permitir contas cracked.

O projeto utiliza Maven e o `pom.xml` está configurado para compilar com Java 21.

## 📁 Arquivos de dados

O plugin utiliza arquivos YAML para persistência local, incluindo:

- `playerdata.yml` — contas locais e dados de autenticação.
- Arquivo de contas Premium utilizado pelo `PremiumAccountManager`.
- `config.yml` — configurações do plugin.
- `mensagens/titulos.yml` — Titles exibidos durante a autenticação.

## 🧪 Build

Para gerar o JAR:

```bash
mvn -B clean package
```

O projeto possui workflow de build no GitHub Actions para validação automática.

## 📌 Observações importantes

### Premium não significa dono exclusivo do nick

O plugin foi projetado para servidores offline-mode. Portanto, a existência de um nick Premium na Mojang **não impede o registro local desse nick**.

A propriedade da conta dentro do servidor é determinada pelo registro local e sua senha.

### Conta local sempre vence a verificação Premium

Se existir uma conta local, o Premium owner do mesmo nick também precisará conhecer a senha local. Isso é comportamento intencional e não é considerado uma falha.

### Verificação Premium com falha não bloqueia o jogador indefinidamente

Se a Mojang não responder ou a sessão não puder ser confirmada dentro do tempo limite, o fluxo segue como cracked para que o jogador possa usar sua conta local.

---

**AuthSystem 1.1.3** — autenticação Premium + Cracked para servidores Spigot offline-mode, com foco em segurança, proteção contra bypass, força bruta e persistência consistente.