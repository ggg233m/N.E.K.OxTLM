# 酒狐插件

酒狐插件用于连接《车万女仆》模组与 N.E.K.O，让玩家能够通过自然语言控制游戏内女仆，并使用跟随、工作模式切换、自主寻路、资源采集、自动找矿和陪玩等能力。

## 运行环境

| 组件 | 当前目标版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.172 或更高的 21.x 版本 |
| Java | 21 |
| 《车万女仆》模组 | 1.5.3 |
| 酒狐插件 | 1.0.7 |
| `neko_tlm_bridge` 桥接模组 | 1.0.7 |

插件与桥接模组默认通过 `ws://127.0.0.1:48920` 通信。如果修改了游戏端端口，必须在插件配置中同步修改 `ws_url`。

## 安装与启动

1. 将 `neko_tlm_bridge-1.0.7.jar` 放入 Minecraft 的 `mods` 目录。
2. 通过 N.E.K.O 的插件安装功能导入 `neko_tlm-1.0.7.neko-plugin`。
3. 先启动 Minecraft 并进入存档，再启动 N.E.K.O 和酒狐插件。
4. 连接成功后，在插件面板中选择要控制的女仆。

插件运行时依赖 `websockets>=15.0.1,<16`。发布包必须包含由依赖同步命令生成的 `vendor/` 目录，不能把 N.E.K.O 安装目录中的同名文件夹当作插件依赖目录。

## 源码目录与同步关系

本项目的主仓库是 `N.E.K.OxTLM`，插件源码位于主仓库的 `neko_tlm/` 子目录。

主仓库 `master` 分支中的 `neko_tlm/**` 发生变化后，根目录工作流 `.github/workflows/sync-neko-tlm-plugin.yml` 会将该子目录同步到以下独立插件仓库的 `main` 分支：

```text
<仓库所有者>/n.e.k.o_plugin_neko_tlm
```

同步到独立插件仓库后，本目录中的 `.github/workflows/` 会成为该仓库根目录下的工作流。它们不会以嵌套目录形式在 `N.E.K.OxTLM` 主仓库中直接触发。

市场检查会临时把独立插件仓库挂载到 N.E.K.O 源码树中的以下位置：

```text
N.E.K.O/plugin/plugins/neko_tlm
```

该路径只是上游检查和打包时使用的挂载位置，不表示本项目需要修改 N.E.K.O 宿主源码。

## 本地开发检查

在 `N.E.K.OxTLM` 主仓库根目录执行：

```bash
python -m pytest neko_tlm/tests -q
uvx ruff==0.12.4 check --ignore-noqa --config neko_tlm/ruff.toml neko_tlm
```

在独立插件仓库根目录执行静态检查时，使用：

```bash
uvx ruff==0.12.4 check --ignore-noqa --config ruff.toml .
```

`ruff` 的规则以 `ruff.toml` 为准，当前检查 `E4`、`E7`、`E9`、`F` 和 `I`，并排除自动生成的 `vendor/`。

## 依赖同步与上游检查

Python 运行时依赖声明在 `pyproject.toml` 中。`vendor/` 是生成目录，不提交到 Git；发布前必须使用 N.E.K.O 插件命令行工具重新生成。

插件已经挂载到 N.E.K.O 源码树后，在 N.E.K.O 源码仓库根目录执行：

```bash
uv run --with pip python -m plugin.neko_plugin_cli.cli sync neko_tlm --clean
uv run python -m plugin.neko_plugin_cli.cli check neko_tlm
uv run python -m plugin.neko_plugin_cli.cli check -r neko_tlm
```

第一条命令根据 `pyproject.toml` 重新生成 `vendor/`；后两条命令分别执行普通检查和发布检查。

## 本地打包

主仓库根目录的 `pack.py` 会读取 `neko_tlm/plugin.toml`，并生成带版本号的本地安装包：

```bash
python pack.py
```

当前输出文件为：

```text
neko_tlm-1.0.7.neko-plugin
```

`pack.py` 只会打包当前已经存在的 `vendor/`，不会下载或同步依赖。因此，在依赖发生变化或准备正式发布时，必须先执行依赖同步。

## 市场发布

市场发布在独立插件仓库中进行，不是在 `N.E.K.OxTLM` 主仓库中直接给插件打发布标签。

市场审核通过且确认同名标签尚不存在后，在独立插件仓库中创建与 `plugin.toml` 版本一致的标签：

```bash
git tag v1.0.7
git push origin v1.0.7
```

独立插件仓库根目录下的 `.github/workflows/release.yml` 会调用 N.E.K.O 的市场发布工作流，重新同步依赖、执行发布检查并创建 GitHub 发布。正式市场产物名为：

```text
neko_tlm.neko-plugin
```

发布中还会包含检查报告和市场证据文件。市场页面应使用该 GitHub 发布中的正式产物，不应使用主仓库中由 `pack.py` 生成的本地调试包代替市场产物。

## 插件入口

插件入口以 `plugin.toml` 为准：

```toml
entry = "plugin.plugins.neko_tlm:NekoMinecraftPlugin"
```

当前插件编号为 `neko_tlm`，版本为 `1.0.7`。
