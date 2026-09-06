# AuthSystem — Login/Registro híbrido para Spigot 26.2

Plugin de autenticação híbrida para servidores `online-mode=false`:

- **Conta premium:** a entrada automática acontece somente depois de uma prova criptográfica de posse da sessão Mojang.
- **Conta cracked:** entra no fluxo normal de `/login` ou `/registro`.
- Usar o nick de outra conta premium **não é suficiente** para ganhar login automático.

## Como a autenticação premium funciona

O plugin não confia mais apenas na existência do nick na Mojang.

1. O cliente envia `LOGIN_START`.
2. O plugin consulta o nome apenas para decidir se vale a pena iniciar o desafio premium.
3. Para um candidato premium, o plugin envia um `Encryption Request` com uma chave RSA e um token aleatório.
4. O cliente premium responde com o segredo AES e o token criptografados.
5. O plugin valida o token, ativa AES/CFB8 e calcula o `serverId/hash` do protocolo Minecraft.
6. O plugin consulta o `sessionserver.mojang.com/hasJoined`.
7. Só se a Mojang confirmar a sessão daquele cliente o jogador recebe o status premium e entra automaticamente.
8. Se a sessão não for confirmada, o jogador continua como cracked e precisa de `/login` ou `/registro`.

Portanto, um cliente cracked usando o nick de uma conta premium não consegue simplesmente passar pela checagem de nome.

## Dependência obrigatória

O AuthSystem usa **PacketEvents 2.13.0+** para interceptar o handshake de login. A versão 2.13.0 adicionou suporte ao Minecraft 26.2 e também mantém compatibilidade com versões antigas suportadas pelo PacketEvents.

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
