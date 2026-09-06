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
└── src/main/
    ├── java/com/authsystem/
    │   ├── AuthSystem.java          (classe principal)
    │   ├── commands/
    │   │   ├── LoginCommand.java
    │   │   └── RegisterCommand.java
    │   ├── listeners/
    │   │   ├── AuthListener.java        (fluxo principal: congela jogador, detecta premium)
    │   │   └── AntiBypassListener.java  (bloqueia formas indiretas de burlar o congelamento)
    │   ├── manager/
    │   │   ├── PlayerDataManager.java  (salva senhas com hash em playerdata.yml)
    │   │   ├── SessionManager.java     (estado em memória: logado, premium, etc.)
    │   │   └── LoginProtection.java    (bloqueio por IP após senhas erradas em excesso)
    │   └── util/
    │       ├── PasswordUtils.java   (hash PBKDF2 + salt)
    │       └── PremiumChecker.java  (consulta a API da Mojang)
    └── resources/
        ├── plugin.yml
        └── config.yml
```

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

A checagem de "é premium ou não" (classe `PremiumChecker`) funciona
consultando a API pública da Mojang para saber se aquele **nome de
usuário** pertence a uma conta original. É a abordagem mais simples e
comum em plugins desse tipo, mas tem uma limitação importante: como o
servidor roda em `offline-mode`, essa checagem **não prova
criptograficamente** que quem está conectando agora é o dono de fato
daquela conta — ela só confirma que aquele nick *existe* como conta
paga na Mojang. Em teoria, alguém poderia digitar o nick de outra
pessoa e ser tratado como "original".

Se você quiser uma verificação realmente à prova de falsificação, a
forma correta é reimplementar o handshake de autenticação da Mojang
(reproduzindo o que o online-mode faz internamente), o que normalmente
é feito com **ProtocolLib** interceptando os pacotes de criptografia do
login — é exatamente assim que plugins prontos como o **FastLogin**
funcionam. Isso é bem mais complexo e foge do escopo de um plugin
simples; se quiser, posso te ajudar a implementar essa versão avançada
depois.

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
