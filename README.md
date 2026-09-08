# LoginPlus

Plugin de autenticação para servidores Minecraft **Spigot 26.2**, desenvolvido para servidores `offline-mode/cracked`, com suporte a contas locais e verificação Premium.

## ✨ Funcionalidades

### 🔐 Autenticação por senha
- `/login <senha>` para contas registradas.
- `/registro <senha> <confirmar-senha>` para criar contas locais.
- Senhas protegidas com **PBKDF2-HMAC-SHA256**, salt aleatório e 600.000 iterações para novos hashes.
- Compatibilidade e atualização de hashes antigos.
- Validação configurável de tamanho e formato da senha.
- Processamento pesado de PBKDF2 fora da thread principal.
- Proteção contra operações simultâneas de login e registro.

### 👑 Verificação Premium
- Verificação real da sessão Premium, sem confiar somente no nick.
- Handshake criptográfico com RSA 2048 e AES/CFB8.
- Consulta ao Session Server da Mojang.
- Autenticação automática quando a sessão Premium é confirmada.
- Timeout, limite de consultas e limpeza de verificações pendentes.
- Se a verificação Premium não for confirmada, o jogador segue pelo fluxo cracked conforme a configuração.
- **Conta local sempre tem prioridade** sobre a autenticação Premium do mesmo nick.

### 🛡️ Anti-bypass
Enquanto não estiver autenticado, o jogador é protegido contra ações que poderiam permitir acesso ao servidor antes do login, incluindo movimento, chat, interação, inventários, blocos, entidades, veículos e outras ações indiretas.

### 🚫 Proteção contra força bruta
- Tentativas incorretas controladas por IP e por conta.
- Bloqueio temporário após exceder o limite configurado.
- Expiração das proteções antigas.

### 🌐 Controle de sessões
- Limite configurável de contas autenticadas por IP.
- Limite configurável de IPs por conta.
- Controle das sessões autenticadas e reservas de conexão.

### 💾 Persistência
- Contas locais e Premium armazenadas em YAML.
- Salvamento assíncrono para reduzir impacto no servidor.
- Snapshots consistentes e proteção contra gravações concorrentes.
- Salvamento seguro durante o desligamento.

## 🎮 Comandos

| Comando | Função |
|---|---|
| `/login <senha>` | Autentica uma conta registrada. |
| `/registro <senha> <confirmar-senha>` | Cria uma conta local. |
| `/register <senha> <confirmar-senha>` | Alias de `/registro`. |
| `/cadastrar <senha> <confirmar-senha>` | Alias de `/registro`. |

## 🔗 Integração

O LoginPlus fornece o estado de autenticação para os demais plugins do ecossistema. O **CargoPlus**, por exemplo, utiliza essa informação para aplicar cargos e permissões somente a jogadores autenticados.

## 🏗️ Plataforma

- Java 26
- Spigot API 26.2
- Maven
- PacketEvents

## 🧪 Build

```bash
mvn -B clean package
```

O projeto possui workflow de build no GitHub Actions.
