<div align="center">

# 🔐 AuthSystem

### Autenticação Premium + Cracked para Spigot 26.2

**Seguro, simples e sem dor de cabeça para o jogador.**

[![Build](https://github.com/donnie1337/sistemalogin/actions/workflows/build.yml/badge.svg)](https://github.com/donnie1337/sistemalogin/actions/workflows/build.yml)

</div>

---

## ✨ Sobre o projeto

O **AuthSystem** é um plugin de autenticação para servidores Minecraft que permite utilizar **contas Premium (Original)** e **Cracked (Pirata)** no mesmo servidor.

A conta Premium é identificada por uma **verificação criptográfica real durante o handshake do Minecraft**, seguida da validação da sessão junto à Mojang. Portanto, o sistema **não confia apenas no nickname**.

Já as contas Cracked utilizam o fluxo tradicional de **registro + login**.

> 🎯 **Objetivo:** contas Originais entram automaticamente; contas Piratas precisam se registrar e fazer login.

---

## 🚀 Funcionalidades

| Recurso | Descrição |
|---|---|
| 🟢 **Login Premium automático** | Contas Originais são verificadas durante o handshake e liberadas sem `/login`. |
| 🔐 **Verificação criptográfica** | Desafio RSA + criptografia AES/CFB8 durante a autenticação. |
| 🌐 **Validação Mojang** | A sessão é confirmada através do serviço `hasJoined`. |
| 🔴 **Login Cracked** | Contas não confirmadas como Premium seguem `/registro` e `/login`. |
| 🛡️ **Anti-bypass** | Proteções contra diversas formas de contornar a autenticação. |
| 🌐 **Limite de contas por IP** | Define quantas contas podem ser cadastradas pelo mesmo IP. |
| 👤 **Limite de IPs por conta** | Define quantos IPs diferentes podem utilizar uma mesma conta. |
| 🔑 **Senhas protegidas** | PBKDF2WithHmacSHA256 + salt aleatório. |
| 🔒 **Regras de senha** | Senhas possuem tamanho mínimo e máximo configuráveis e exigem letras e números. |
| 🚨 **Anti-força bruta** | Limite de tentativas e bloqueio temporário por IP. |
| ⏱️ **Tempo limite** | Expulsa jogadores que não concluem a autenticação dentro do prazo. |
| 🧊 **Congelamento** | Jogadores não autenticados ficam impedidos de realizar ações não autorizadas. |
| 🏠 **Teleportes de plugins** | Teleportes causados por plugins continuam funcionando para spawn/lobby. |
| 🎨 **Títulos configuráveis** | Mensagens de registro e login podem ser personalizadas. |
| 💾 **Persistência** | Dados das contas e limites de IP permanecem após reinicializações. |
| 🔄 **Migração de configuração** | Configurações antigas de senha são atualizadas automaticamente para o novo sistema. |
| 🤖 **Validação no build** | O GitHub Actions verifica o JAR e as configurações essenciais antes de considerar o build válido. |

---

## 🔑 Comandos

### Registro

```text
/registro <senha> <confirmar-senha>
```

Aliases:

```text
/register <senha> <confirmar-senha>
/cadastrar <senha> <confirmar-senha>
```

### Login

```text
/login <senha>
```

### Regras da senha

A senha precisa:

- Ter **no mínimo 7 caracteres** por padrão;
- Ter **no máximo 16 caracteres** por padrão;
- Conter pelo menos **uma letra**;
- Conter pelo menos **um número**;
- Conter somente **letras e números**;
- Não possuir espaços ou caracteres especiais.

Os limites mínimo e máximo podem ser alterados no `config.yml`.

Exemplos:

```text
MinhaSenha123
Senha123
```

Exemplos inválidos:

```text
senha123       # menos de 7 caracteres
MinhaSenha123456789 # mais de 16 caracteres
12345678       # não possui letra
MinhaSenha     # não possui número
Senha@123      # possui caractere especial
```

> ⚠️ O plugin valida a senha tanto no comando de registro quanto no gerenciador de dados, evitando que chamadas internas contornem as regras configuradas.

---

## 👑 Autenticação Premium

O AuthSystem não identifica uma conta Original simplesmente pelo nome do jogador.

O processo é baseado em uma verificação criptográfica durante o login:

```text
Cliente Minecraft
       │
       ▼
  LOGIN_START
       │
       ▼
Desafio criptográfico
       │
       ▼
ENCRYPTION_RESPONSE
       │
       ▼
Validação do token
       │
       ▼
Criptografia AES/CFB8
       │
       ▼
Verificação da sessão na Mojang
       │
       ├──── Confirmada ────► 👑 Premium
       │
       └──── Não confirmada ► 🔴 Cracked
```

A identidade Premium também é validada antes de liberar o acesso automático.

### Fallback

Se a sessão Premium não for confirmada, a conexão **não é tratada automaticamente como Premium**. O jogador segue o fluxo normal de conta Cracked.

Isso evita que alguém consiga entrar como Original simplesmente utilizando o nickname de outra pessoa.

---

## 🛡️ Sistema Anti-Bypass

O jogador permanece limitado até concluir a autenticação.

Entre as proteções implementadas estão:

- Bloqueio de comandos não autorizados;
- Bloqueio do chat;
- Congelamento do jogador;
- Bloqueio de interações;
- Bloqueio de inventários não permitidos;
- Bloqueio de veículos;
- Bloqueio de projéteis;
- Bloqueio de alimentação;
- Proteção contra determinadas formas de dano;
- Proteção contra interações com entidades;
- Proteção contra tentativas indiretas de contornar a autenticação.

### Teleporte

Existe uma exceção importante: **teleportes causados por plugins não são bloqueados**.

Isso permite que plugins de spawn, lobby ou sistemas semelhantes possam posicionar o jogador normalmente enquanto ele ainda está aguardando autenticação.

---

## 🌐 Limites por IP

### Contas por IP

Controla quantas **contas diferentes podem ser cadastradas pelo mesmo IP**.

```yaml
max-contas-por-ip: 1
```

| Valor | Comportamento |
|:---:|---|
| `1` | Uma conta por IP |
| `2` | Até duas contas por IP |
| `3` | Até três contas por IP |
| `0` | Sem limite |

### IPs por conta

Controla quantos **IPs diferentes podem utilizar a mesma conta**.

```yaml
max-ips-por-conta: 1
```

| Valor | Comportamento |
|:---:|---|
| `1` | Apenas o primeiro IP autorizado |
| `2` | Até dois IPs diferentes |
| `3` | Até três IPs diferentes |
| `0` | Sem limite |

O sistema mantém compatibilidade com contas antigas que possuem somente o campo `ip` e também utiliza a lista `ips` para armazenar novos endereços autorizados.

---

## 🚨 Proteção contra força bruta

O `LoginProtection` controla as tentativas de `/login` por endereço IP.

Configuração padrão:

```yaml
max-tentativas-login: 3
bloqueio-apos-exceder-tentativas-minutos: 5
```

Após exceder o número permitido de tentativas, o IP é temporariamente bloqueado.

Isso evita que o jogador simplesmente desconecte e conecte novamente para zerar as tentativas.

---

## ⚙️ Configuração

Arquivo:

```text
plugins/AuthSystem/config.yml
```

Configuração padrão atual:

```yaml
tempo-limite-login-segundos: 60
max-tentativas-login: 3
bloqueio-apos-exceder-tentativas-minutos: 5
minimo-caracteres-senha: 7
maximo-caracteres-senha: 16
max-contas-por-ip: 1
max-ips-por-conta: 1
```

### Senhas

O tamanho da senha é definido por:

```yaml
minimo-caracteres-senha: 7
maximo-caracteres-senha: 16
```

Além do tamanho, o plugin exige pelo menos uma letra e um número e permite somente caracteres alfanuméricos ASCII.

### Atualizações de configuração

O AuthSystem preserva as configurações já existentes e adiciona automaticamente novas configurações que ainda não estejam presentes no arquivo.

Configurações antigas que foram substituídas também são migradas automaticamente. A opção antiga `tamanho-minimo-senha` é removida durante a inicialização e substituída pelas configurações `minimo-caracteres-senha` e `maximo-caracteres-senha`.

Isso evita que um `config.yml` antigo continue usando regras de senha desatualizadas.

---

## 💾 Armazenamento

Os dados das contas ficam em:

```text
plugins/AuthSystem/playerdata.yml
```

Uma conta registrada pode possuir:

- Nome da conta;
- Hash da senha;
- Salt da senha;
- IP utilizado no cadastro;
- Lista de IPs conhecidos;
- Data do registro.

🔒 **As senhas não são armazenadas em texto puro.**

---

## 🎨 Títulos

Os títulos da autenticação podem ser configurados em:

```text
plugins/AuthSystem/mensagens/titulos.yml
```

Exemplo:

```yaml
bem-vindo: "&aBem-vindo"
registro: "&eFaça o registro"
login: "&eFaça o login"
```

---

## 📦 Estrutura do projeto

```text
AuthSystem/
├── pom.xml
├── README.md
├── src/main/
│   ├── java/com/authsystem/
│   │   ├── AuthSystem.java
│   │   ├── commands/
│   │   │   ├── LoginCommand.java
│   │   │   └── RegisterCommand.java
│   │   ├── listeners/
│   │   │   ├── AuthListener.java
│   │   │   ├── AntiBypassListener.java
│   │   │   └── PremiumVerificationListener.java
│   │   ├── manager/
│   │   │   ├── PlayerDataManager.java
│   │   │   ├── SessionManager.java
│   │   │   └── LoginProtection.java
│   │   └── util/
│   │       ├── PasswordUtils.java
│   │       ├── PremiumAuthenticator.java
│   │       └── PremiumLoginVerifier.java
│   └── resources/
│       ├── plugin.yml
│       ├── config.yml
│       └── mensagens/
│           └── titulos.yml
```

---

## 🔒 Segurança

Principais mecanismos de segurança:

- Verificação criptográfica de contas Premium;
- Validação da sessão junto à Mojang;
- Validação da identidade Premium antes da liberação;
- PBKDF2WithHmacSHA256 para armazenamento de senhas;
- Salt aleatório por senha;
- Comparação em tempo constante para credenciais e tokens sensíveis;
- Regras configuráveis de tamanho de senha;
- Restrição de senha a letras e números;
- Limite de tentativas por IP;
- Bloqueio temporário contra força bruta;
- Limite de contas por IP;
- Limite de IPs por conta;
- Proteção contra bypass de autenticação;
- Expiração das informações temporárias usadas na verificação Premium;
- Estruturas concorrentes para gerenciamento das sessões online;
- Validações repetidas no fluxo de registro para evitar contornos internos das regras.

---

## 🧪 Desenvolvimento e CI

O projeto utiliza **GitHub Actions** para validar automaticamente o build.

O pipeline:

1. Configura o **Java 26**;
2. Baixa o `BuildTools.jar`;
3. Gera o **Spigot 26.2**;
4. Compila o plugin com Maven;
5. Verifica se o JAR final foi gerado;
6. Confere arquivos essenciais dentro do JAR;
7. Valida as configurações padrão de senha e limites por IP;
8. Garante que a configuração antiga `tamanho-minimo-senha` não permaneça no JAR.

---

## 📋 Requisitos

- **Java 26** para o ambiente de build do projeto;
- **Spigot/Paper** compatível com a API utilizada;
- **PacketEvents 2.13.0+**.

O PacketEvents deve estar instalado no servidor em:

```text
plugins/
```

---

<div align="center">

### 🔐 AuthSystem

**Autenticação Premium e Cracked com foco em segurança e simplicidade.**

</div>
