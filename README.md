# AuthSystem — Plugin de Autenticação para Spigot 26.2

O **AuthSystem** é um plugin de autenticação para servidores Minecraft que permite a utilização simultânea de contas **Premium (Original)** e **Cracked (Pirata)**. Contas Premium são verificadas automaticamente por meio de um processo criptográfico e da validação da sessão junto à Mojang, sem depender apenas do nickname. Contas Cracked utilizam o sistema tradicional de registro e login.

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
    │       └── PremiumLoginVerifier.java
    └── resources/
        ├── plugin.yml
        ├── config.yml
        └── mensagens/
            └── titulos.yml
```

## Funcionalidades

- Autenticação automática de contas Premium.
- Verificação Premium baseada em desafio criptográfico durante o handshake do Minecraft.
- Validação da sessão através do serviço `hasJoined` da Mojang.
- Contas Cracked seguem o fluxo tradicional de `/registro` e `/login`.
- Um jogador Cracked não pode obter acesso Premium apenas utilizando o nickname de uma conta Original.
- Senhas protegidas com `PBKDF2WithHmacSHA256` e salt aleatório.
- Senhas de registro com 7 a 16 caracteres, contendo pelo menos uma letra e um número.
- Limite configurável de contas diferentes por endereço IP.
- Limite configurável de IPs diferentes que podem utilizar a mesma conta.
- Proteção contra bypass do limite de contas e do processo de autenticação.
- Limite de tentativas de login por IP.
- Bloqueio temporário de IP após excesso de tentativas.
- Tempo limite configurável para login e registro.
- Congelamento de jogadores enquanto não estão autenticados.
- Bloqueio de comandos, chat e diversas interações não autorizadas durante a autenticação.
- Proteção contra interações indiretas envolvendo teleporte, inventários, veículos, entidades, projéteis, alimentação e outras ações.
- Teleportes causados por plugins são preservados para permitir sistemas de spawn/lobby.
- Títulos configuráveis para registro e login.
- Preservação das configurações existentes durante atualizações.
- Inclusão automática de novas configurações ausentes.
- Estruturas de sessão preparadas para acesso concorrente entre threads.

## Autenticação Premium

O AuthSystem **não considera apenas o nickname** para identificar uma conta Premium. A autenticação utiliza um desafio criptográfico durante o handshake e posteriormente confirma a sessão junto à Mojang.

O fluxo funciona da seguinte maneira:

1. `PremiumVerificationListener` intercepta o início do login.
2. O servidor envia um desafio de criptografia com chave pública RSA e token de verificação.
3. O cliente responde com os dados protegidos por RSA.
4. O plugin valida o token e estabelece a criptografia AES/CFB8.
5. `PremiumLoginVerifier` calcula o `serverId/hash` utilizado na autenticação.
6. A sessão é consultada através do serviço `hasJoined` da Mojang.
7. A resposta é comparada com a identidade enviada no login antes de marcar o jogador como Premium.
8. Após a confirmação, a prova Premium é registrada temporariamente.
9. Quando o jogador entra no servidor, `AuthListener` consome a prova e libera o acesso automaticamente.
10. Caso a sessão não seja confirmada, o jogador segue o fluxo de conta Cracked.

A verificação possui também um fallback para que uma conexão que não conclua o desafio Premium possa continuar pelo fluxo normal de autenticação.

## Contas Piratas

Contas que não são confirmadas como Premium utilizam o sistema tradicional de autenticação.

Para criar uma conta, utiliza-se:

```text
/registro <senha> <confirmar-senha>
```

A senha deve possuir entre **7 e 16 caracteres**, contendo pelo menos uma letra e um número.

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

O endereço IP utilizado no cadastro é armazenado junto aos dados da conta em `playerdata.yml`. O controle é feito com base nos IPs conhecidos das contas e permanece válido após reinicializações do servidor.

## Limite de IPs por conta

O AuthSystem também permite definir quantos **endereços IP diferentes podem utilizar a mesma conta**.

A configuração padrão é:

```yaml
max-ips-por-conta: 1
```

Os valores disponíveis são:

| Valor | Comportamento |
|---|---|
| `1` | A conta fica vinculada ao primeiro IP utilizado |
| `2` | A conta pode ser utilizada por até dois IPs diferentes |
| `3` | A conta pode ser utilizada por até três IPs diferentes |
| `0` | Remove o limite |

Os IPs conhecidos são armazenados em `playerdata.yml`. O sistema mantém compatibilidade com contas antigas que possuem apenas o campo `ip` e também utiliza a lista `ips` para os novos endereços autorizados.

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

Teleportes causados por plugins são mantidos para não interferir em sistemas de spawn, lobby ou outros plugins responsáveis por posicionar o jogador após a entrada.

### Proteção contra força bruta

`LoginProtection` controla as tentativas de login por endereço IP. Depois de exceder o limite configurado, o IP fica temporariamente bloqueado.

## Configurações

O arquivo principal é gerado em:

```text
plugins/AuthSystem/config.yml
```

Configuração padrão atual:

```yaml
tempo-limite-login-segundos: 60
max-tentativas-login: 3
bloqueio-apos-exceder-tentativas-minutos: 5
max-contas-por-ip: 1
max-ips-por-conta: 1
```

As senhas são fixadas em **7 a 16 caracteres**, com pelo menos uma letra e um número, e não possuem mais configurações separadas de tamanho mínimo/máximo no `config.yml`.

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
- Lista de IPs conhecidos da conta.
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

## Segurança

O AuthSystem foi desenvolvido com foco na proteção do processo de autenticação.

Entre as principais medidas implementadas estão:

- Verificação criptográfica para contas Premium.
- Validação da sessão junto à Mojang.
- Comparação da identidade Premium antes de liberar o acesso automático.
- Hash de senha utilizando `PBKDF2WithHmacSHA256`.
- Salt aleatório por senha.
- Controle de tentativas de login por IP.
- Bloqueio temporário após excesso de tentativas.
- Limitação de contas por IP.
- Limitação de IPs por conta.
- Proteção contra bypass através de reconexões e interações indiretas.
- Bloqueio de ações enquanto o jogador não estiver autenticado.
- Expiração das informações temporárias utilizadas na autenticação Premium.
- Estruturas concorrentes para o gerenciamento das sessões online.

## Desenvolvimento e CI

O projeto utiliza GitHub Actions para validar automaticamente a compilação.

O pipeline prepara o ambiente com Java 26, gera o Spigot 26.2 através do `BuildTools.jar`, compila o plugin com Maven e verifica se o JAR final contém os arquivos e configurações essenciais.
