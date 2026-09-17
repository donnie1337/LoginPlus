# LoginPlus

O LoginPlus é o sistema de login e registro do meu servidor.

Ele foi feito para servidor Spigot 26.2 em `offline-mode/cracked`, mas também verifica contas Premium de verdade quando possível.

A ideia é simples: o jogador entra, faz login ou registro e, depois de autenticado, os outros plugins podem liberar os recursos normalmente.

## Login e registro

- `/login <senha>` faz login em uma conta já registrada.
- `/registro <senha> <confirmar-senha>` cria uma conta local.
- `/register <senha> <confirmar-senha>` é alias de `/registro`.
- `/cadastrar <senha> <confirmar-senha>` também é alias de `/registro`.
- Senhas protegidas com PBKDF2-HMAC-SHA256.
- Salt aleatório para os novos hashes.
- 600.000 iterações para novos hashes.
- Compatibilidade com hashes antigos e atualização quando necessário.
- Tamanho e formato mínimo da senha configuráveis.
- O processamento pesado da senha não fica preso na thread principal.

## Verificação Premium

O LoginPlus também tenta confirmar se o jogador possui uma sessão Premium válida.

- Verificação real da sessão.
- Handshake criptográfico com RSA 2048 e AES/CFB8.
- Consulta ao Session Server da Mojang.
- Login automático quando a sessão Premium é confirmada.
- Timeout e limite de consultas configuráveis.
- Limpeza das verificações que ficaram pendentes.
- Se a verificação Premium não for confirmada, o jogador segue pelo sistema cracked configurado.
- Conta local tem prioridade quando existe uma conta registrada para o mesmo nick.

## Proteção antes do login

Enquanto o jogador ainda não estiver autenticado, o LoginPlus bloqueia ações que poderiam permitir que ele usasse o servidor antes de fazer login.

Isso inclui coisas como:

- Movimento.
- Chat.
- Interação.
- Inventários.
- Blocos.
- Entidades.
- Veículos.
- Outras ações protegidas pelo sistema.

## Proteção contra tentativas de senha

O plugin também possui proteção contra tentativa de força bruta.

- Controle de tentativas por IP.
- Controle de tentativas por conta.
- Bloqueio temporário quando o limite é atingido.
- Expiração das proteções antigas.

## Controle de sessões

- Limite de contas autenticadas por IP.
- Limite de IPs por conta.
- Controle das sessões autenticadas.
- Reserva de conexão para evitar conflitos durante o login.

## Dados

As contas ficam salvas em YAML.

O salvamento é feito de forma assíncrona sempre que possível, com proteção contra gravações concorrentes e salvamento seguro quando o servidor é desligado.

## Integração com os outros plugins

O LoginPlus fornece para os outros plugins a informação de que o jogador já foi autenticado.

Por exemplo, o CargoPlus usa esse estado para não entregar permissões de cargo para um jogador que ainda não fez login.

## Comandos

| Comando | O que faz |
|---|---|
| `/login <senha>` | Faz login na conta. |
| `/registro <senha> <confirmar-senha>` | Cria uma conta local. |
| `/register <senha> <confirmar-senha>` | Alias de `/registro`. |
| `/cadastrar <senha> <confirmar-senha>` | Alias de `/registro`. |

## Plataforma

- Java 26
- Spigot API 26.2
- Maven
- PacketEvents

## Build

```bash
mvn -B clean package
```

O projeto possui build automático pelo GitHub Actions.

## Status

O LoginPlus está em desenvolvimento e é o responsável pelo sistema de autenticação do meu servidor. A ideia é manter o login seguro, simples para o jogador e bem integrado com os outros plugins.
