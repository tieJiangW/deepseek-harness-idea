package com.deepseek.harness.idea.ui

/**
 * JCEF 页面内 composer 注入脚本的构造器（纯函数，可单测；见 docs/DESIGN.md §3.7/§3.11）。
 *
 * **背景（v0.2.4，真实页面 CDP 实测）**：dsh 0.1.5 的输入框由 `<textarea>` 改为
 * **Lexical `contenteditable`**（`<div data-lexical-editor="true" role="textbox" contenteditable="true">`），
 * 旧的 `document.querySelector('textarea')` 永远找不到元素，两个动作遂"没反应"。
 *
 * 实测确认有效的写入/验证方式（本对象的契约）：
 * 1. 选择器顺序：`textarea` → `[data-lexical-editor="true"]` → `[contenteditable="true"][role="textbox"]`
 *    → `div[contenteditable="true"]`；
 * 2. 写入 contenteditable 用 `document.execCommand('insertText')`——Chromium 会派发 `beforeinput`，
 *    而 Lexical 正是通过该事件同步内部状态（纯 DOM 赋值不会被接受）；失败再退回合成 `paste`；
 * 3. **回读必须读 `[data-lexical-text="true"]` 节点并轮询**：写入当拍 Lexical 的 `innerText`/`textContent`
 *    可能为空（实测），"写后立即回读"会把成功误判为失败；
 * 4. 自动提交派发 `KeyboardEvent('keydown')`（带 `key/code/keyCode/which`），随后轮询编辑器是否清空判定
 *    `submitted`；否则退回点击 `button[aria-label="Send message"/"发送消息"/"Send"/"发送"]`（绝不用 class 通配，
 *    避免误点运行中的"停止"）。
 */
object ComposerScripts {

    /** 注入结果：未找到输入框（页面未就绪/前端改版）。 */
    const val OUTCOME_NOT_FOUND = "notfound"

    /** 注入结果：写入未生效。 */
    const val OUTCOME_FAILED = "failed"

    /** 注入结果：已填入输入框（发送选中代码场景）。 */
    const val OUTCOME_INJECTED = "injected"

    /** 注入结果：已提交（一键解释场景）。 */
    const val OUTCOME_SUBMITTED = "submitted"

    /** 注入结果：填入成功但未能自动提交（需用户按回车）。 */
    const val OUTCOME_BLOCKED = "blocked"

    /**
     * 构造注入脚本。
     *
     * @param text 要写入输入框的文本（自动转成 JSON 字符串字面量）
     * @param submit true = 写入后自动提交（一键解释）；false = 只填入等用户输入（发送选中代码）
     * @param funcName `JBCefJSQuery` 的函数名；为 null 时不回传结果（调用方按"无法验证"处理）
     */
    fun build(text: String, submit: Boolean, funcName: String?): String {
        val json = escapeJs(text)
        val submitFlag = submit.toString()
        // 注意：变量名不叫 report —— JS 侧有 `$report(` 调用，`$r` 会被 Kotlin 当作模板表达式。
        val reportCode = if (funcName != null) {
            "const report = (o) => { try { window.$funcName({ request: o, onSuccess: () => {}, onFailure: () => {} }); } catch (e) {} };"
        } else {
            "const report = () => {};"
        }
        return """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              const shouldSubmit = $submitFlag;
              $reportCode

              const editor = () => document.querySelector('textarea')
                || document.querySelector('[data-lexical-editor="true"]')
                || document.querySelector('[contenteditable="true"][role="textbox"]')
                || document.querySelector('div[contenteditable="true"]');

              // dsh 的 composer 是 Lexical：根元素 innerText/textContent 可能为空，
              // 内容在 [data-lexical-text="true"] 节点里（文本与引用 chip 各占一些节点）。
              const editorText = (el) => {
                if (!el) return '';
                if (el.tagName === 'TEXTAREA') return el.value || '';
                const parts = el.querySelectorAll('[data-lexical-text="true"]');
                if (parts.length > 0) return Array.from(parts).map((n) => n.textContent || '').join('\n');
                return (el.innerText || '') + '\n' + (el.textContent || '');
              };
              const norm = (s) => String(s).replace(/\s+/g, ' ').trim();
              /** 去掉所有空白后的内容视图：读写断词、节点切分都不影响它。 */
              const squash = (s) => String(s).replace(/\s+/g, '');
              const want = squash(text);
              /**
               * 输入框是否**已经包含**这段引用（据此决定跳过注入，避免重复）。
               *
               * 判据（真实页面实测得出）：
               * - 去掉全部空白后比较：dsh 会把 `@…/` 目录 token 渲染成引用 chip，chip 的 textContent
               *   只覆盖路径片段，且节点之间**不含空白**，按空白边界匹配会判成"不存在"而重复注入；
               * - 要求读回到的内容**长度量级相近**：避免只读到一个片段（如 `@…/resources/` 恰好是
               *   目标串的子串）就误判成"已存在"而漏掉真正的写入。
               */
              const hasTarget = (el) => {
                const got = squash(editorText(el));
                if (want.length === 0 || got.length === 0) return false;
                return got.indexOf(want) >= 0 && got.length >= want.length * 0.8;
              };
              const isEmptyEditor = (el) => squash(editorText(el)).length === 0;

              const placeCaret = (el) => {
                try {
                  const sel = window.getSelection();
                  const r = document.createRange();
                  r.selectNodeContents(el);
                  r.collapse(false);
                  sel.removeAllRanges();
                  sel.addRange(r);
                  el.focus();
                } catch (e) {}
              };

              /**
               * 只做一次写入尝试（**绝不叠加第二种写入方式**）。
               *
               * 历史 bug（v0.2.4 实测定位）：`insertText` 是**同步生效但异步更新 DOM** 的，
               * 曾经的实现"写后立即回读 → 判为空 → 再派发一次 paste"就会**同一份写两遍**，
               * 用户看到 `@a#L7-11@a#L7-11`。现在写入只走 `insertText`（Chromium 会派发
               * `beforeinput`，Lexical 据此同步内部状态，实测有效），失效由外层"轮询 + 重试"兜底。
               */
              const write = (el) => {
                el.focus();
                placeCaret(el);
                if (el.tagName === 'TEXTAREA') {
                  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                  setter.call(el, text);
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  const pos = el.value.length;
                  try { el.setSelectionRange(pos, pos); } catch (e) {}
                  return;
                }
                try { document.execCommand('insertText', false, text); } catch (e) {}
                placeCaret(el);
              };

              const sendButton = () => document.querySelector(
                'button[aria-label="Send message"], button[aria-label="发送消息"], button[aria-label="Send"], button[aria-label="发送"]');
              const cleared = (el) => isEmptyEditor(el);

              const submitNow = (el) => {
                placeCaret(el);
                el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                const t0 = Date.now();
                const poll = () => {
                  if (cleared(el)) { report('submitted'); return; }
                  if (Date.now() - t0 < 3000) { setTimeout(poll, 250); return; }
                  const btn = sendButton();
                  if (btn && !btn.disabled) {
                    btn.click();
                    setTimeout(() => report(cleared(el) ? 'submitted' : 'blocked'), 500);
                  } else {
                    report('blocked');
                  }
                };
                setTimeout(poll, 500);
              };

              const attempt = (tryNo) => {
                const el = editor();
                if (!el) {
                  if (Date.now() < deadline) { setTimeout(() => attempt(tryNo), 300); return; }
                  report('notfound');
                  return;
                }
                // 已包含同一引用 → 不重复写入（dsh 会把 @路径 渲染为文件引用 chip，重复注入会叠加两份）
                if (hasTarget(el)) {
                  if (shouldSubmit) submitNow(el); else report('injected');
                  return;
                }
                const before = squash(editorText(el));
                write(el);

                // 写入不保证当拍生效（Lexical 的 DOM 更新有一拍延迟），且空编辑器首写可能整段不生效
                // —— 因此轮询确认，未生效则在 8s 窗口内重试（实测：首次 insertText 常见无效果）。
                const verify = (verifyDeadline) => {
                  if (hasTarget(el)) {
                    if (shouldSubmit) submitNow(el); else report('injected');
                    return;
                  }
                  const now = squash(editorText(el));
                  const grew = now.length > before.length && now !== before;
                  if (grew && !shouldSubmit) { report('injected'); return; }
                  if (Date.now() < verifyDeadline) { setTimeout(() => verify(verifyDeadline), 200); return; }
                  if (Date.now() < deadline) { setTimeout(() => attempt(tryNo + 1), 250); return; }
                  report('failed');
                };
                verify(Date.now() + 1200);
              };
              attempt(1);
            })();
        """.trimIndent()
    }

    /** 把文本转成可安全内联进 JS 的双引号字符串字面量。 */
    fun escapeJs(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
