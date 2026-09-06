# AuthSystem — Login/Registro híbrido para Spigot 26.2

Plugin de autenticação híbrida para servidores `online-mode=false`:

- **Conta premium:** a entrada automática acontece somente depois de uma prova criptográfica de posse da sessão Mojang.
- **Conta cracked:** entra no fluxo normal de `/login` ou `/registro`.
- Usar o nick de outra conta premium **não é suficiente** para ganhar login automático.

## Como a autenticação premium funciona

O plugin não confia apenas na existência do nick na Mojang e não depende de uma consulta prévia ao nome para decidir se o jogador é premium.

1. O cliente envia `LOGIN_START`.
2. O plugin pausa o processamento normal desse pacote e envia um `Encryption Request` com uma chave RSA e um token aleatório.
3. Um cliente que consegue responder ao desafio envia o segredo AES e o token criptografados.
4. O plugin valida o token, ativa AES/CFB8 e calcula o `serverId/hash` do protocolo Minecraft.
5. O plugin consulta `sessionserver.mojang.com/session/minecraft/hasJoined`.
6. Só se a Mojang confirmar a sessão daquele cliente o jogador recebe o status premium e entra automaticamente.
7. Se a Mojang não confirmar a sessão, o plugin continua o mesmo login como cracked.
8. Se um cliente cracked não responder ao `Encryption Request`, após um pequeno timeout o plugin também continua o login normal como cracked.

Isso evita falsos positivos por nome, evita depender da API pública de consulta de nomes e permite que premium e cracked usem o mesmo servidor `online-mode=false`.

## Dependência obrigatória

O AuthSystem usa **PacketEvents 2.13.0+** para interceptar o handshake de login. A versão 2.13.0 adicionou suporte ao Minecraft 26.2.

Instale `packetevents-spigot-2.13.0.jar` na pasta `plugins/` antes de iniciar o servidor.

O `plugin.yml` declara PacketEvents como dependência obrigatória.

## Servidor

Para aceitar premium e cracked no mesmo servidor:

```properties
online-mode=false
```

Não use proxy para o sistema de autenticação.

## Comandos

| Comando | Descrição |
|---|---|
| `/login <senha>` | Faz login numa conta cracked já registrada |
| `/registro <senha> <confirmar-senha>` | Cria uma conta cracked |

## Proteção de login

Jogadores não autenticados ficam congelados e não podem usar comandos, chat, interação, quebra/colocação de blocos, dano ou outras formas comuns de bypass.

Tentativas incorretas são controladas por IP conforme `config.yml`.

## Compilação

Java 21+ para compilar o plugin e Java 25 para executar o servidor 26.x.

Depois de instalar/gerar as dependências:

```bash
mvn clean package
```

O JAR final será criado em:

```text
target/AuthSystem.jar
```
