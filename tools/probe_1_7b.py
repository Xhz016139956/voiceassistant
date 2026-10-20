"""用真实的 qwen3:1.7b 跑一遍 TaskAgent 的格式要求，看到底行不行。"""
import json
import time
import urllib.request

SYSTEM = """你是手机操作员。系统会把任务拆成若干子目标，你**每次只负责其中一个子目标**。
每一轮只输出一个 JSON 动作；系统执行后会告诉你「执行结果」和「最新屏幕」，你再决定下一步。
禁止解释、禁止 markdown、禁止一次输出多个动作。

可用动作（只能选这些）：
{"action":"open_app","app_name":"微信"}
{"action":"click_index","index":3}
{"action":"click_text","text":"确定"}
{"action":"input_text","text":"晚上一起吃饭"}
{"action":"swipe","direction":"up|down|left|right","distance":0.6}
{"action":"back"}
{"action":"home"}
{"action":"volume","command":"up|down|mute|unmute"}
{"action":"speak_screen"}
{"action":"send_message","app_name":"微信","contact":"老王","content":"晚上一起吃饭"}
{"action":"wait","seconds":2}
{"action":"done","summary":"一句话说做了什么"}
{"action":"ask","question":"要问用户的话"}
{"action":"none"}

规则：
1. 一次只做一个动作，不要提前把后面的步骤做完。
2. 点东西优先用 click_index，序号必须来自「可点击元素」列表，不能编造；列表里确实没有才用 click_text。
3. 输入文字前必须先让输入框聚焦：没聚焦就先 click_index 点那个输入框，等结果回来再 input_text。
4. 界面刚跳转、看着还在加载，用 wait，不要重复上一步。
5. 「执行结果」显示失败时，换一种做法，不要原样重试。
6. 给某人发一句话，能一次说清就用 send_message；要先看聊天记录再回才分步做。
7. 任务完成立即输出 done，summary 一句话，会被念出来，别超过 30 字。
8. 只有确实缺关键信息（找谁、发什么、第几个）才用 ask。
9. 用户只是聊天、问问题、不要你动手机 → 输出 {"action":"none"}。

示例（照这个来）：
- 任务「发送」，屏幕可点击：1.[输入框] 怎么使用AI  2.发送
  输出 {"action":"click_index","index":2}
- 任务「打开微信」，屏幕可点击：1.设置  2.微信  3.抖音
  输出 {"action":"click_index","index":2}
- 任务「你觉得AI会取代程序员吗」，屏幕是桌面
  输出 {"action":"none"}
- 任务「输入你好」，屏幕可点击：1.[输入框] 说点什么  2.发送
  输出 {"action":"click_index","index":1}"""

CASES = [
    (
        "桌面第一步",
        """任务：打开deepseek

已执行：无（这是第一步）

当前屏幕：
前台应用：com.miui.home
屏幕文字：设置 | 微信 | 抖音 | DeepSeek | 相机
可点击元素（序号可直接用于 click_index）：
1. 设置
2. 微信
3. 抖音
4. DeepSeek
5. 相机

注意：上面的序号只对本次屏幕有效，屏幕一变就要重新看列表。
输出下一步动作（只输出一个 JSON，不要解释）：""",
    ),
    (
        "已在DeepSeek输入页",
        """任务：输入怎么使用AI

已执行：
1. 打开应用「DeepSeek」 → 已打开「DeepSeek」

当前屏幕：
前台应用：com.deepseek.chat
屏幕文字：新对话 | 有什么可以帮你的 | 发送
可点击元素（序号可直接用于 click_index）：
1. [输入框] 有什么可以帮你的
2. 发送
3. 新对话

注意：上面的序号只对本次屏幕有效，屏幕一变就要重新看列表。
输出下一步动作（只输出一个 JSON，不要解释）：""",
    ),
    (
        "输入完该发送",
        """任务：发送

已执行：
1. 输入「怎么使用AI」 → 已输入「怎么使用AI」

当前屏幕：
前台应用：com.deepseek.chat
屏幕文字：怎么使用AI | 发送
可点击元素（序号可直接用于 click_index）：
1. [输入框] 怎么使用AI
2. 发送

注意：上面的序号只对本次屏幕有效，屏幕一变就要重新看列表。
输出下一步动作（只输出一个 JSON，不要解释）：""",
    ),
    (
        "纯聊天不该动手机",
        """任务：你觉得人工智能会不会取代程序员

已执行：无（这是第一步）

当前屏幕：
前台应用：com.miui.home
屏幕文字：设置 | 微信 | 抖音 | DeepSeek | 相机
可点击元素（序号可直接用于 click_index）：
1. 设置
2. 微信
3. 抖音
4. DeepSeek
5. 相机

注意：上面的序号只对本次屏幕有效，屏幕一变就要重新看列表。
输出下一步动作（只输出一个 JSON，不要解释）：""",
    ),
]


def ask(model, user, temperature):
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "think": False,
        "options": {"temperature": temperature, "num_predict": 120},
    }
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/chat",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    started = time.time()
    with urllib.request.urlopen(req, timeout=180) as r:
        body = json.loads(r.read().decode("utf-8"))
    text = (body.get("message") or {}).get("content", "")
    return text.strip(), time.time() - started


for model in ["qwen3:1.7b"]:
    for temp in [0.6, 0.1]:
        print(f"\n===== {model}  temperature={temp} =====")
        for title, user in CASES:
            try:
                text, cost = ask(model, user, temp)
            except Exception as e:  # noqa: BLE001
                print(f"[{title}] ERROR {type(e).__name__}: {e}")
                continue
            ok = text.startswith("{") and text.endswith("}")
            print(f"[{title}] {cost:.1f}s json={ok} -> {text[:160]!r}")
