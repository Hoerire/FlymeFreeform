#!/system/bin/sh
# 重启闸门 —— 约束九.2 的命令级实现。
# 用途：任何 killall system_server / kill zygote / reboot 之前，必须先把四件事写成一句话，
#       否则本脚本拒绝执行。纪律不靠新会话的记忆，靠这个文件在磁盘上。
#
# 用法（/storage 是 noexec，必须用 sh 显式调用）：
#   sh restart-gate.sh --changed "本轮改了什么" --pkg "包路径 sha256" \
#       --verify "验证什么、期望看到什么" --logs "看哪几条日志" -- ["killall system_server"]
#   sh restart-gate.sh --show         查看最近一次声明
#
# 不带最后一段命令时只记录声明、不执行任何重启。

LOG="$(dirname "$0")/tmp/restart-declarations.log"
CHANGED=""; PKG=""; VERIFY=""; LOGS=""; SHOW=0; CMD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --changed) CHANGED="$2"; shift 2 ;;
    --pkg)     PKG="$2";     shift 2 ;;
    --verify)  VERIFY="$2";  shift 2 ;;
    --logs)    LOGS="$2";    shift 2 ;;
    --show)    SHOW=1; shift ;;
    --) shift; CMD="$*"; break ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

if [ "$SHOW" = "1" ]; then
  if [ -f "$LOG" ]; then tail -n 6 "$LOG"; else echo "(没有记录)"; fi
  exit 0
fi

missing=""
[ -n "$CHANGED" ] || missing="$missing --changed"
[ -n "$PKG" ]     || missing="$missing --pkg"
[ -n "$VERIFY" ]  || missing="$missing --verify"
[ -n "$LOGS" ]    || missing="$missing --logs"
if [ -n "$missing" ]; then
  echo "拒绝：声明不完整，缺:$missing" >&2
  echo "约束九.2：重启前先把话说完——改了什么、装的哪个包、sha256、验证什么、看哪几条日志。" >&2
  exit 3
fi

mkdir -p "$(dirname "$LOG")"
{
  echo "==== $(date '+%Y-%m-%d %H:%M:%S') ===="
  echo "改了什么: $CHANGED"
  echo "包与摘要: $PKG"
  echo "验证什么: $VERIFY"
  echo "看哪条日志: $LOGS"
  echo "命令: ${CMD:-（仅记录，未执行）}"
} | tee -a "$LOG"

if [ -z "$CMD" ]; then
  echo "已记录声明，未执行重启。"
  exit 0
fi

echo "执行: $CMD"
setsid sh -c "$CMD" >/dev/null 2>&1 &
echo "已以 setsid 脱离进程组发起。"
