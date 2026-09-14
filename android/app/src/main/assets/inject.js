/*
 * 壳层注入脚本 —— 随 APK 打包，运行时注入校内 H5 页面。
 *
 * 由 MainActivity 通过 evaluateJavascript 注入，支持三个占位符：
 *   __ACCT__     已保存账号（JSON 字符串），为空时填空串
 *   __PWD__      已保存密码（JSON 字符串），为空时填空串
 *   __BG_VALUE__ 课表背景图 data URI（JSON 字符串），为空时填空串
 *
 * 通信方式：window.__jyBridge（由原生 addJavascriptInterface 注册）
 *   route(href, isFullLoad)  上报当前页面 URL，原生据此维护导航栈
 *   storeCreds(acct, pwd)    用户在登录页完成输入后上报，原生加密保存
 *
 * 整个脚本是幂等的：重复注入通过 __jyReady 标记直接返回，
 * 因此原生可以在每次 onPageLoaded 时安全地重新注入。
 */
(function () {
  if (window.__jyReady) return;
  window.__jyReady = true;

  var ACCT = __ACCT__;
  var PWD = __PWD__;
  window.__jyBg = __BG_VALUE__;

  var bridge = window.__jyBridge;

  function send(name) {
    if (!bridge) return false;
    var args = Array.prototype.slice.call(arguments, 1);
    try {
      bridge[name].apply(bridge, args);
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ---------- 站点特征 ---------- */

  function hash() {
    return location.hash || '';
  }
  function hasAny(s, keys) {
    s = (s || '').toLowerCase();
    for (var i = 0; i < keys.length; i++) {
      if (s.indexOf(keys[i]) >= 0) return true;
    }
    return false;
  }
  function isMinePage() {
    return hasAny(hash(), ['person', 'my', 'mine', 'user']);
  }
  function isSchedulePage() {
    return hasAny(hash(), ['schedule', 'kebiao', 'timetable', 'course']);
  }
  function accountInputs() {
    return {
      acct: document.querySelector('input[placeholder*="学号"],input[placeholder*="工号"],input[autocomplete="user"]'),
      pwd: document.querySelector('input[placeholder*="密码"],input[type="password"]')
    };
  }
  /* React/Vue 受控组件必须走原生 value setter 并派发 input 事件，直接赋值不会同步进框架状态 */
  function setNativeValue(el, value) {
    try {
      var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
      desc.set.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ---------- 站内路由上报 ---------- */

  function report(full) {
    send('route', location.href, !!full);
  }

  /* ---------- “我的”页面右上角设置按钮 ---------- */

  function ensureSettingsButton() {
    var mine = isMinePage();
    var btn = document.getElementById('app-settings-btn');
    var menu = document.getElementById('app-settings-menu');
    if (!mine) {
      if (btn && btn.parentNode) btn.parentNode.removeChild(btn);
      if (menu && menu.parentNode) menu.parentNode.removeChild(menu);
      return;
    }
    if (btn) return;

    btn = document.createElement('div');
    btn.id = 'app-settings-btn';
    btn.textContent = '⚙';
    btn.style.cssText =
      'position:fixed;top:12px;right:12px;z-index:99999;width:36px;height:36px;line-height:36px;' +
      'text-align:center;background:rgba(0,0,0,0.35);color:#fff;font-size:20px;border-radius:50%;';

    btn.onclick = function () {
      var opened = document.getElementById('app-settings-menu');
      if (opened) {
        opened.style.display = opened.style.display === 'none' ? 'block' : 'none';
        return;
      }

      var box = document.createElement('div');
      box.id = 'app-settings-menu';
      box.style.cssText =
        'position:fixed;top:54px;right:12px;z-index:99999;background:#fff;border-radius:8px;' +
        'box-shadow:0 2px 12px rgba(0,0,0,0.2);padding:6px 0;min-width:130px;';

      var bgItem = document.createElement('div');
      bgItem.textContent = '更换课表主题';
      bgItem.style.cssText = 'padding:12px 16px;font-size:14px;color:#333;cursor:pointer;text-align:center;';
      bgItem.onclick = function () {
        send('chooseBackground');
      };
      box.appendChild(bgItem);

      var logoutItem = document.createElement('div');
      logoutItem.textContent = '退出账号';
      logoutItem.style.cssText = 'padding:12px 16px;font-size:14px;color:#d33;cursor:pointer;text-align:center;';
      logoutItem.onclick = function () {
        send('logout');
      };
      box.appendChild(logoutItem);

      document.body.appendChild(box);
    };

    document.body.appendChild(btn);
  }

  /* ---------- 课表页自定义背景 ---------- */

  var BG_STYLE_ID = 'app-bg-style';

  function syncBackground() {
    var style = document.getElementById(BG_STYLE_ID);
    if (!isSchedulePage() || !window.__jyBg) {
      if (style && style.parentNode) style.parentNode.removeChild(style);
      return;
    }
    if (!style) {
      style = document.createElement('style');
      style.id = BG_STYLE_ID;
      document.head.appendChild(style);
    }
    style.textContent =
      'html,body{background-image:url("' + window.__jyBg + '")!important;' +
      'background-size:cover!important;background-position:center!important;' +
      'background-attachment:fixed!important;}';
  }

  /* ---------- 登录框自动填充 / 凭证学习 ---------- */

  /* 只上报「与原生下发的初始值不同」的凭证，避免每次失焦都重复跨线程序列化 */
  var lastAcct = ACCT;
  var lastPwd = PWD;

  function tryFill() {
    if (!ACCT || !PWD) return false;
    var el = accountInputs();
    if (!el.acct || !el.pwd) return false;
    if (el.acct.value && el.pwd.value) return true;
    if (!el.acct.value) setNativeValue(el.acct, ACCT);
    if (!el.pwd.value) setNativeValue(el.pwd, PWD);
    return true;
  }

  function sendCreds() {
    var el = accountInputs();
    if (!el.acct || !el.pwd) return;
    var a = el.acct.value;
    var p = el.pwd.value;
    if (!a || !p) return;          // 用户可能只填了一半，别覆盖已存的好凭证
    if (a === lastAcct && p === lastPwd) return;
    if (send('storeCreds', a, p)) {
      lastAcct = a;
      lastPwd = p;
    }
  }

  /* 登录页可能在 SPA 切路由后才渲染出来，短时间内重试几次；次数有上限，不会常驻轮询 */
  function fillWithRetry(delays) {
    var i = 0;
    function step() {
      if (tryFill()) return;
      if (i < delays.length) setTimeout(step, delays[i++]);
    }
    step();
  }

  function onPageReady() {
    ensureSettingsButton();
    syncBackground();
    report(true);
    fillWithRetry([150, 400, 900, 1600]);
  }

  /* ---------- 事件绑定（只绑一次，替代原来的 1.5s 轮询） ---------- */

  window.addEventListener('hashchange', function () {
    report(false);
    ensureSettingsButton();
    syncBackground();
    fillWithRetry([100, 350, 800]);
  });

  document.addEventListener('focusin', function (e) {
    if (!e.target || !e.target.tagName || e.target.tagName.toLowerCase() !== 'input') return;
    sendCreds();   // 捕获「自动填充后用户又手动改过」的场景
  }, true);

  document.addEventListener('blur', function (e) {
    if (!e.target || !e.target.tagName || e.target.tagName.toLowerCase() !== 'input') return;
    sendCreds();
  }, true);

  window.__jyPageReady = onPageReady;
  window.__jySetBg = function (value) {
    window.__jyBg = value || '';
    syncBackground();
  };

  /* 原生通过 evaluateJavascript 调 __jyPageReady；DOM 已就绪但 SPA 首屏可能还没渲染完，
     这里再补一次兜底调用。 */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onPageReady);
  } else {
    onPageReady();
  }
})();
