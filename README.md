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
    │       └── PremiumLoginVerifier.java  (desafio RSA/AES e verificação na sessão Mojang)
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

## Sobre a detecção de conta original — leia isso

A checagem de "é premium ou não" agora utiliza o handshake criptográfico implementado por `PremiumVerificationListener` e `PremiumLoginVerifier`.
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
   óbvias de escapar do congelamento: teleporte de jogadores por causas
   não relacionadas a plugins, abrir baús/inventários, montar em cavalo/barco,
   interagir com entidades, atirar flechas/itens, comer, mobs mirando no jogador,
   e o próprio jogador causando dano em algo. Teleportes causados por plugins
   são permitidos para que sistemas de spawn/lobby possam posicionar o jogador.
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

## Limite de contas por IP

O plugin permite limitar quantas contas diferentes podem ser cadastradas
usando o mesmo endereço IP. Essa proteção é configurável no `config.yml`
por meio de `max-contas-por-ip`.

```yaml
max-contas-por-ip: 1
```

- `1` = permite apenas uma conta por IP.
- `2` = permite até duas contas por IP.
- `0` = desativa o limite.

O endereço IP utilizado no cadastro é armazenado junto aos dados da conta.
Assim, o limite é aplicado aos novos registros sem impedir o login de contas
que já existem.

## Regras de senha

As senhas das contas precisam seguir estas regras no `/registro`:

- Mínimo de **7 caracteres**.
- Máximo de **16 caracteres**.
- Pelo menos **uma letra**.
- Pelo menos **um número**.

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
max-contas-por-ip: 1                        # quantidade maxima de contas por IP
```
