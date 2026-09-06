# AuthSystem — Plugin de Autenticação para Spigot 26.2

O **AuthSystem** é um plugin de autenticação para servidores Minecraft que permite a utilização simultânea de contas **Premium** e **Cracked**. Contas Premium são verificadas automaticamente por meio de um processo criptográfico e não precisam utilizar `/login` ou `/registro`. Contas Cracked utilizam o sistema tradicional de registro e login.

## Estrutura do projeto

```text
AuthSystem/
├── pom.xml
├── README.md
├── AuthSystem.iml
└── src/main/
    ├── java/com/authsystem/
    │   ├── AuthSystem.java
    │   ├── commands/
    │   │   ├── LoginCommand.java
    │   │   └── RegisterCommand.java
    │   ├── listeners/
    │   │   ├── AuthListener.java
    │   │   ├── AntiBypassListener.java
    │   │   └── PremiumVerificationListener.java
    │   ├── manager/
    │   │   ├── PlayerDataManager.java
    │   │   ├── SessionManager.java
    │   │   └── LoginProtection.java
    │   └── util/
    │       ├── PasswordUtils.java
    │       ├── PremiumAuthenticator.java
    │       ├── PremiumLoginVerifier.java
    │       └── PremiumChecker.java
    └── resources/
        ├── plugin.yml
        ├── config.yml
        └── mensagens/
            └── titulos.yml
```

## Funcionalidades

- Autenticação automática de contas Premium.
- Verificação criptográfica durante o handshake do Minecraft.
- Validação da sessão através da Mojang.
- Registro e login para contas Cracked.
- Senhas protegidas com PBKDF2WithHmacSHA256 e salt aleatório.
- Limite configurável de contas por endereço IP.
- Proteção contra bypass do limite de contas e do processo de autenticação.
- Limite de tentativas de login por IP.
- Bloqueio temporário de IP após excesso de tentativas.
- Tempo limite configurável para login e registro.
- Congelamento de jogadores enquanto não estão autenticados.
- Bloqueio de comandos, chat e interações não autorizadas durante a autenticação.
- Títulos configuráveis para registro e login.
- Preservação das configurações existentes durante atualizações.
- Inclusão automática de novas configurações ausentes.

## Autenticação Premium

O AuthSystem não considera apenas o nickname para identificar uma conta Premium. A autenticação utiliza um desafio criptográfico durante o handshake e posteriormente confirma a sessão junto à Mojang.

O fluxo funciona da seguinte maneira:

1. `PremiumVerificationListener` intercepta o início do login.
2. O servidor envia um desafio de criptografia com chave pública RSA e token de verificação.
3. O cliente responde com os dados protegidos por RSA.
4. O plugin valida o token e estabelece a criptografia AES/CFB8.
5. `PremiumLoginVerifier` calcula o `serverId/hash` utilizado na autenticação.
6. A sessão é consultada através do serviço `hasJoined` da Mojang.
7. Somente após a confirmação a prova Premium é registrada temporariamente.
8. Quando o jogador entra no servidor, `AuthListener` consome a prova e libera o acesso automaticamente.
9. Caso a sessão não seja confirmada, o jogador segue o fluxo de conta Cracked.

## Contas Cracked

Contas que não são confirmadas como Premium utilizam o sistema tradicional de autenticação.

Para criar uma conta, utiliza-se:

```text
/registro <senha> <confirmar-senha>
```

Depois do registro, o acesso pode ser realizado com:

```text
/login <senha>
```

Os aliases disponíveis para registro são:

```text
/register <senha> <confirmar-senha>
/cadastrar <senha> <confirmar-senha>
```

Após um registro bem-sucedido, o jogador é autenticado automaticamente.

## Limite de contas por IP

O plugin permite determinar quantas contas diferentes podem ser cadastradas utilizando o mesmo endereço IP.

A configuração padrão é:

```yaml
max-contas-por-ip: 1
```

Os valores disponíveis são:

| Valor | Comportamento |
|---|---|
| `1` | Permite apenas uma conta por IP |
| `2` | Permite até duas contas por IP |
| `3` | Permite até três contas por IP |
| `0` | Remove o limite |

O endereço IP utilizado no cadastro é armazenado junto aos dados da conta em `playerdata.yml`. Dessa forma, o limite permanece válido mesmo após reinicializações do servidor.

## Sistema anti-bypass

O processo de autenticação possui diferentes camadas de proteção.

### Congelamento

Enquanto não estiver autenticado, o jogador não pode:

- Andar livremente.
- Utilizar comandos não autorizados.
- Falar no chat.
- Quebrar ou colocar blocos.
- Causar ou receber determinados tipos de dano.
- Realizar interações que possam contornar a autenticação.

### Proteções indiretas

`AntiBypassListener` também trata formas menos óbvias de escapar do processo de autenticação, incluindo teleporte, inventários, veículos, entidades, projéteis, alimentação e outras interações.

### Proteção contra força bruta

`LoginProtection` controla as tentativas de login por endereço IP. Depois de exceder o limite configurado, o IP fica temporariamente bloqueado.

## Configurações

O arquivo principal é gerado em:

```text
plugins/AuthSystem/config.yml
```

Configuração padrão:

```yaml
# Tempo (em segundos) que o jogador tem para digitar /login ou /registro
tempo-limite-login-segundos: 60

# Número de erros de senha permitidos antes do bloqueio
max-tentativas-login: 3

# Tempo (em minutos) que o IP fica bloqueado após exceder o limite
bloqueio-apos-exceder-tentativas-minutos: 5

# Tamanho mínimo exigido para a senha no /registro
tamanho-minimo-senha: 4

# Quantas contas diferentes podem ser cadastradas pelo mesmo IP.
# 1 = apenas uma conta por IP.
# 2 = até duas contas por IP.
# 0 = sem limite.
max-contas-por-ip: 1
```

O AuthSystem não substitui as configurações existentes ao ser atualizado. Quando uma nova configuração ainda não estiver presente no `config.yml`, ela será adicionada automaticamente sem remover os valores já configurados pelo servidor.

## Armazenamento

Os dados das contas são armazenados em:

```text
plugins/AuthSystem/playerdata.yml
```

Cada conta registrada possui informações como:

- Nome da conta.
- Hash da senha.
- Salt da senha.
- Endereço IP utilizado no cadastro.
- Data do registro.

As senhas não são armazenadas em texto puro.

## Títulos

Os títulos exibidos durante o processo de autenticação podem ser configurados em:

```text
plugins/AuthSystem/mensagens/titulos.yml
```

Exemplo:

```yaml
bem-vindo: "&aBem-vindo"
registro: "&eFaça o registro"
login: "&eFaça o login"
```

## Requisitos

- Java 21 ou superior compatível com o ambiente de compilação.
- Spigot/Paper compatível com a API utilizada pelo projeto.
- PacketEvents 2.13.0 ou superior.

O PacketEvents deve estar instalado no servidor em `plugins/`.

## Configuração do servidor

No `server.properties`, a configuração deve ser:

```properties
online-mode=false
```

Essa configuração permite que contas Premium e Cracked utilizem o mesmo servidor, enquanto o AuthSystem realiza a verificação adicional das contas Premium.

## Segurança

O AuthSystem foi desenvolvido com foco na proteção do processo de autenticação.

Entre as principais medidas implementadas estão:

- Verificação criptográfica para contas Premium.
- Validação da sessão junto à Mojang.
- Hash de senha utilizando PBKDF2WithHmacSHA256.
- Salt aleatório por senha.
- Controle de tentativas de login por IP.
- Bloqueio temporário após excesso de tentativas.
- Limitação de contas por IP.
- Proteção contra bypass através de reconexões e interações indiretas.
- Bloqueio de ações enquanto o jogador não estiver autenticado.
- Expiração de informações temporárias utilizadas na autenticação Premium.
