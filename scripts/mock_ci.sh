#!/bin/sh
# 在 Demo 中串联已安装 CLI 的上传和下载，任一步失败立即退出。
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/.."
lingora "$@" push
exec lingora "$@" pull
