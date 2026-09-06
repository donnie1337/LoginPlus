# AuthSystem — Plugin de Login/Registro para Spigot 26.2

Plugin de autenticação com `/login` e `/registro`. Contas **originais
(premium)** são detectadas automaticamente e não precisam logar nem se
registrar; contas **piratas (cracked)** ficam "congeladas" (sem mover,
falar no chat, quebrar/colocar blocos, tomar dano, etc.) até efetuarem
login ou registro.

## Estrutura do projeto

```
AuthSystem/
├── pom.xml
├── README.md
├── config.yml
├── playerdata.yml
├── AuthSystem.iml
└── src/main/
    ├── java/com/authsystem/
    │   ├── AuthSystem.java                (classe principal e registro dos componentes)
    │   ├── commands/
    │   │   ├── LoginCommand.java          (comando /login)
    │   │   └── RegisterCommand.java       (comando /registro)
    │   ├── listeners/
    │   │   ├── AuthListener.java          (fluxo de autenticação e proteção do jogador)
    │   │   ├── AntiBypassListener.java    (bloqueia formas indiretas de burlar o login)
    │   │   └── PremiumVerificationListener.java (handshake criptográfico de conta premium)
    │   ├── manager/
    │   │   ├── PlayerDataManager.java     (dados, senhas e vínculo das contas com IPs)
    │   │   ├── SessionManager.java        (estado das sessões autenticadas)
    │   │   └── LoginProtection.java       (bloqueio por excesso de tentativas)
    │   └── util/
    │       ├── PasswordUtils.java         (hash PBKDF2 + salt)
    │       ├── PremiumAuthenticator.java  (guarda provas premium verificadas temporariamente)
    │       ├── PremiumLoginVerifier.java  (desafio RSA/AES e verificação na sessão Mojang)
    │       └── PremiumChecker.java        (consulta auxiliar de conta premium)
    └── resources/
        ├── plugin.yml
        └── config.yml
```

## Funcionalidades

### Autenticação premium

- Detecta contas originais automaticamente.
- Não depende apenas do nickname para identificar uma conta premium.
- Usa desafio criptográfico durante o handshake de login.
- Valida o token de verificação antes de ativar a criptografia.
- Usa RSA + AES/CFB8 para o desafio de autenticação.
- Consulta a sessão da Mojang através do `hasJoined`.
- A prova premium é temporária, vinculada ao IP e consumida quando o jogador entra.
- Se a sessão não for confirmada pela Mojang, o jogador continua como cracked e precisa usar `/login` ou `/registro`.

### Contas cracked

- `/registro` para criar uma conta.
- `/login` para acessar uma conta existente.
- Senhas armazenadas com hash PBKDF2 + salt.
- Senhas exigem tamanho mínimo e devem conter letras e números.
- Jogadores não autenticados ficam congelados até concluir o login ou registro.
- Existe um tempo máximo para concluir a autenticação.

### Limite de contas por IP

O plugin permite limitar quantas contas podem ser cadastradas usando o mesmo endereço IP.

Por padrão, o limite é de **1 conta por IP**. Esse valor pode ser alterado no `config.yml`.

- `1` = apenas uma conta por IP.
- `2` = até duas contas por IP.
- `3` = até três contas por IP.
- `0` = sem limite.

O IP utilizado no cadastro é salvo junto aos dados da conta em `playerdata.yml`.

### Sistema anti-bypass

Três camadas trabalham juntas para impedir que alguém contorne o login:

1. **Congelamento (`AuthListener`)** — enquanto não autenticado, o jogador
   não anda, não fala no chat, não usa comandos além de `/login` e
   `/registro`, não quebra/coloca blocos, não toma dano nem perde fome.
2. **Proteções indiretas (`AntiBypassListener`)** — cobre formas menos
   óbvias de escapar do congelamento: teleporte, abrir baús/inventários,
   montar em cavalo/barco, interagir com entidades, atirar flechas/itens,
   comer, mobs mirando no jogador, e o próprio jogador causando dano em algo.
3. **Bloqueio por senha errada (`LoginProtection`)** — limita as tentativas
   de login por endereço IP. Depois de exceder o limite configurado, o IP
   fica temporariamente bloqueado e novas tentativas são barradas antes do
   jogador entrar no servidor.

O histórico de tentativas antigas também é removido da memória depois de um
período de inatividade, evitando manter registros desnecessários indefinidamente.

## Fluxo da autenticação premium

A autenticação de contas originais não depende apenas do nick. O fluxo atual usa um desafio criptográfico durante o login:

1. `PremiumVerificationListener` intercepta o `LOGIN_START`.
2. O servidor envia um `Encryption Request` com chave pública RSA e token de verificação.
3. O cliente responde com o token e uma chave AES compartilhada, ambos protegidos por RSA.
4. O plugin valida o token e ativa AES/CFB8 na conexão.
5. `PremiumLoginVerifier` calcula o `serverId/hash` usando a chave AES e a chave pública do servidor.
6. O plugin consulta a `hasJoined` da Mojang para confirmar a sessão.
7. Somente após a confirmação criptográfica a prova é registrada em `PremiumAuthenticator`.
8. Quando o jogador realmente entra, `AuthListener` consome essa prova e libera o login automaticamente.
9. Se a Mojang não confirmar a sessão, o fluxo continua como conta cracked e o jogador precisa usar `/login` ou `/registro`.

## Comandos

| Comando | Descrição |
|---|---|
| `/login <senha>` | Faz login numa conta já registrada |
| `/registro <senha> <confirmar-senha>` | Cria uma nova conta |
| `/register <senha> <confirmar-senha>` | Alias de `/registro` |
| `/cadastrar <senha> <confirmar-senha>` | Alias de `/registro` |

Enquanto o jogador não estiver autenticado, apenas os comandos de autenticação permitidos ficam disponíveis.

## Configurações (`config.yml`)

```yaml
# Tempo (em segundos) que o jogador tem para digitar /login ou /registro
tempo-limite-login-segundos: 60

# Número de erros de senha permitidos antes de bloquear o IP
max-tentativas-login: 3

# Tempo (em minutos) que o IP fica bloqueado após exceder o limite de tentativas
bloqueio-apos-exceder-tentativas-minutos: 5

# Tamanho mínimo exigido para a senha no /registro
tamanho-minimo-senha: 4

# Quantas contas diferentes podem ser cadastradas pelo mesmo IP.
# 1 = apenas uma conta por IP.
# 0 = sem limite.
max-contas-por-ip: 1
```

## Armazenamento

Os dados das contas são armazenados em `playerdata.yml` dentro da pasta do plugin.

Cada conta registrada possui:

- Nome da conta.
- Hash da senha.
- Salt da senha.
- Endereço IP usado no cadastro.
- Data do registro.

O armazenamento atual é baseado em YAML, sem dependências externas. Para servidores muito grandes, uma futura migração para SQLite ou MySQL pode ser considerada.

## ⚠️ Passo obrigatório antes de compilar: gerar o spigot-api local

A Mojang não permite que o Spigot redistribua o jar da API já pronto.
Por isso, antes de rodar `mvn package`, você precisa gerar esse artefato
localmente **uma vez**, usando o BuildTools oficial:

```bash
# 1. Baixe o BuildTools.jar no site oficial do Spigot.
# 2. Rode, pedindo exatamente a versão 26.2:
java -jar BuildTools.jar --rev 26.2

# Isso instala automaticamente o spigot-api-26.2-R0.1-SNAPSHOT.jar
# no seu repositório Maven local (~/.m2/repository).
```

Isso baixa e compila os arquivos da Mojang/Spigot — então você precisa
de internet liberada para os domínios do Mojang/Spigot/Maven nesse passo.

Java necessário: o Minecraft/Spigot 26.x exige **Java 25** para rodar o
servidor. Para compilar o BuildTools e o plugin, use também uma JDK 21+
(recomendo instalar a 25 para ficar tudo alinhado).

## Compilando o plugin

Depois do passo acima, dentro da pasta `AuthSystem/`:

```bash
mvn clean package
```

O arquivo gerado fica em `target/AuthSystem.jar`. Copie esse `.jar` para
a pasta `plugins/` do seu servidor Spigot e reinicie.

## Configuração do servidor

No `server.properties`, deixe:

```
online-mode=false
```

Isso permite que contas cracked e contas originais entrem no mesmo servidor.

## Sobre a detecção de conta original

A checagem de conta original utiliza o handshake criptográfico implementado por
`PremiumVerificationListener` e `PremiumLoginVerifier`. O plugin não confia
apenas no nick: a confirmação depende da resposta de criptografia do cliente
e da validação da sessão na Mojang.

Isso impede que outra pessoa simplesmente digite o nick de uma conta original
e seja tratada como dona daquela conta.
