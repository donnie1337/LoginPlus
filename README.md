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
- Nomes comuns podem ser usados por jogadores cracked mesmo quando pertencem a uma conta Premium; eles seguem `/login` ou `/registro`.
- Falhas de DNS, rota, firewall, timeout ou API não autenticam ninguém. Para nomes comuns, o jogador segue pelo fluxo local de `/login` ou `/registro`.
- A existência de um perfil Premium não autentica o jogador. Só uma sessão confirmada pelo Session Server ativa o auto-login.
- O dono de uma conta Premium que também usa o fluxo local pode definir `/premiumfallback` depois de um auto-login válido. Para identidades de cargo alto, apenas essa senha explicitamente definida pode servir de contingência.
- Uma identidade protegida por integração de cargo não pode ser registrada por um jogador novo; contas locais protegidas existentes ainda exigem `/login`.
- Como o servidor está em `online-mode=false`, duas pessoas que usem o mesmo nickname compartilham a mesma identidade offline e os dados do jogador. Proteja com a integração `protectIdentity` todos os nomes que dão acesso a cargos ou outros privilégios.
- O auto-login Premium continua ativo para todas as contas, inclusive cargos altos.
- `/premiumfallback <senha> <confirmar-senha>` permite ao dono definir uma senha local de contingência depois de entrar por uma sessão Premium verificada. A senha local antiga nunca é habilitada automaticamente para esse uso.

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
- Controle de tentativas por IP e por combinação conta/IP, sem bloquear globalmente uma conta que um atacante escolheu como alvo.
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

## Configuração de rede e logs

- O servidor precisa resolver DNS e abrir conexões HTTPS de saída (TCP 443) para `sessionserver.mojang.com` e `api.mojang.com` para verificar sessões e perfis Premium. Indisponibilidade não bloqueia nomes comuns do fluxo cracked, mas também nunca concede auto-login.
- O Paper registra comandos de jogadores em `spigot.yml` por padrão. Como `/login`, `/registro` e `/premiumfallback` carregam senhas nos argumentos, defina `commands.log: false` para não gravar esses segredos no console/`latest.log`. Isso desativa o log de todos os comandos de jogadores.

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
| `/premiumfallback <senha> <confirmar-senha>` | Define/atualiza uma senha de contingência depois do auto-login Premium confirmado. |

## Plataforma

- Java 26
- Spigot/Paper API 26.2
- Maven
- PacketEvents

## Build

```bash
mvn -B clean package
```

O projeto possui build automático pelo GitHub Actions.

## Status

O LoginPlus está em desenvolvimento e é o responsável pelo sistema de autenticação do meu servidor. A ideia é manter o login seguro, simples para o jogador e bem integrado com os outros plugins.
