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
    │   │   ├── PlayerDataManager.java     (dados e senhas dos jogadores)
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

### Fluxo da autenticação premium

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

## ⚠️ Passo obrigatório antes de compilar: gerar o spigot-api local

A Mojang não permite que o Spigot redistribua o jar da API já pronto.
Por isso, antes de rodar `mvn package`, você precisa gerar esse artefato
localmente **uma vez**, usando o BuildTools oficial:

```bash
# 1. Baixe o BuildTools.jar (link sempre atualizado em spigotmc.org):
#    https://www.spigotmc.org/wiki/buildtools/

# 2. Rode, pedindo exatamente a versão 26.2:
java -jar BuildTools.jar --rev 26.2

# Isso instala automaticamente o spigot-api-26.2-R0.1-SNAPSHOT.jar
# no seu repositório Maven local (~/.m2/repository).
```

Isso baixa e compila os arquivos da Mojang/Spigot — então você precisa
de internet liberada para os domínios do Mojang/Spigot/Maven nesse passo
(não dá pra fazer isso num ambiente sem acesso à internet).

Java necessário: o Minecraft/Spigot 26.x exige **Java 25** para RODAR o
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

Isso é o que permite tanto contas piratas (que passam por /login e
/registro) quanto contas originais entrarem no mesmo servidor. Se
`online-mode=true`, só quem tem conta original consegue nem conectar —
nesse caso o plugin não teria função alguma, pois o próprio Minecraft
já garante que 100% dos jogadores são donos legítimos da conta.

## Sobre a detecção de conta original — leia isso

A checagem de "é premium ou não" agora utiliza o handshake criptográfico
implementado por `PremiumVerificationListener` e `PremiumLoginVerifier`.
O plugin não confia apenas no nick: a confirmação depende da resposta de
criptografia do cliente e da validação da sessão na Mojang.

Isso impede que outra pessoa simplesmente digite o nick de uma conta
original e seja tratada como dona daquela conta. A prova premium também
é vinculada ao endereço IP e mantida por pouco tempo, sendo consumida
quando o jogador efetivamente entra no servidor.

## Sistema anti-bypass

Três camadas trabalham juntas para impedir que alguém contorne o login:

1. **Congelamento (`AuthListener`)** — enquanto não autenticado, o jogador
   não anda, não fala no chat, não usa comandos além de `/login` e
   `/registro`, não quebra/coloca blocos, não toma dano nem perde fome.
2. **Proteções indiretas (`AntiBypassListener`)** — cobre formas menos
   óbvias de escapar do congelamento: teleporte, abrir baús/inventários,
   montar em cavalo/barco, interagir com entidades, atirar flechas/itens,
   comer, mobs mirando no jogador, e o próprio jogador causando dano em algo.
3. **Bloqueio por senha errada (`LoginProtection`)** — é a parte que você
   pediu: se errar a senha mais vezes do que `max-tentativas-login`
   permite (padrão: 3 erros, expulso no 4º), o jogador é desconectado.

   O importante aqui: a contagem de erros é feita **por endereço IP**, não
   pela sessão do jogador. Isso fecha a brecha mais óbvia de bypass — sem
   isso, bastaria a pessoa se desconectar e reconectar repetidamente para
   "zerar" o contador e continuar tentando senhas para sempre (força
   bruta). Com o IP bloqueado por `bloqueio-apos-exceder-tentativas-minutos`
   (padrão: 5 minutos), mesmo reconectando ela é barrada já no pré-login,
   antes até de entrar no servidor.

## Comandos

| Comando | Descrição |
|---|---|
| `/login <senha>` | Faz login numa conta já registrada |
| `/registro <senha> <confirmar-senha>` | Cria uma nova conta |

## Configurações (`config.yml`)

```yaml
tempo-limite-login-segundos: 60             # tempo para logar antes do kick
max-tentativas-login: 3                     # erros de senha permitidos antes do kick
bloqueio-apos-exceder-tentativas-minutos: 5 # bloqueio de IP apos exceder o limite acima
tamanho-minimo-senha: 4                     # tamanho minimo da senha no /registro
```
