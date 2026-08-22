package com.axon.input;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 应用内 HTML API 文档页。 */
public final class HtmlGuideActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK
                ? R.style.AppThemeBlack : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        applySystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiPalette.background(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(36));
        root.setBackgroundColor(UiPalette.background(this));

        addTitle(root, "Axon Input HTML API · v10");
        addBody(root,
                "HTML 可以完全接管支持组件的视觉层。每个悬浮窗口独立运行一份页面。"
                + "支持 HTML、CSS、JavaScript、SVG、Canvas、Web Animations 与本地存储。\n\n"
                + "Android 只提供输入和只读配置，不暴露系统高权限接口；网络访问保持关闭。");

        addSection(root, "1. 最小模板");
        addCode(root, """
<!doctype html>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}
#root{width:100%;height:100%;display:grid;place-items:center;color:var(--kd-text-primary)}
</style>
<div id="root"></div>
<script>
KeyDisplay.on('init', e => render(e.detail));
KeyDisplay.on('update', e => render(e.detail));
function render(state){
  root.textContent = state.type;
}
</script>
""");

        addSection(root, "2. 页面可以读取什么");
        addBody(root,
                "state.apiVersion / rendererVersion：API 版本。\n"
                + "state.type：当前窗口类型。\n"
                + "state.theme：light 或 dark。\n"
                + "state.sizePercent：窗口显示倍率。\n"
                + "state.viewport：宽、高、density。\n"
                + "state.keys：键盘、鼠标、自定义键位。\n"
                + "state.pointer：鼠标移动增量。\n"
                + "state.mouseButtons：鼠标左右键状态。\n"
                + "state.gamepad：摇杆、扳机、按键、CPS。\n"
                + "state.config：当前显示的外观、间距、透明度和功能开关。\n"
                + "state.palette：完整原生调色板。\n"
                + "state.runtime：拖动、灵敏度倍率、窗口位置、会话持久化策略等运行信息。\n"
                + "state.settings：应用显示、外观、手柄兼容和 HTML 设置的只读快照。\n"
                + "state.capabilities：当前窗口可用能力列表。");

        addSection(root, "3. Axon Input 辅助 API");
        addCode(root, """
console.log(KeyDisplay.apiVersion); // 10
console.log(KeyDisplay.version);    // v30
console.log(KeyDisplay.type);       // keyboard / mouse / ...

const state = KeyDisplay.getState();
const off = KeyDisplay.on('key', e => console.log(e.detail));
// 不再需要时：off();

KeyDisplay.once('init', e => console.log('只执行一次', e.detail));
console.log(KeyDisplay.types);
console.log(KeyDisplay.has('gamepad'));
console.log(KeyDisplay.clamp(1.6,0,1));
console.log(KeyDisplay.lerp(0,100,.25));
console.log(KeyDisplay.map(.5,0,1,0,100));
console.log(KeyDisplay.deadzone(.12,.08));
KeyDisplay.css('--my-radius', '14px');
KeyDisplay.cssAll({'--gap':'8px','--press':'#fff'});
""");

        addSection(root, "4. v10 状态辅助 API");
        addCode(root, """
const w = KeyDisplay.findKey('w');
const pressed = KeyDisplay.button('a');
const x = KeyDisplay.axis('lx');
const cps = KeyDisplay.cps('l1');

// 自动立即读取一次完整状态，之后监听 update。
const stopState = KeyDisplay.onState(state => render(state));

// 一次订阅多个输入事件。
const stopInput = KeyDisplay.onInput({
  key: k => updateKey(k),
  pointer: p => movePointer(p),
  gamepad: g => updatePad(g)
});
// 不需要时执行 stopState() / stopInput()。
""");

        addSection(root, "5. 自动 CSS 变量");
        addCode(root, """
.card{
  color:var(--kd-text-primary);
  background:var(--kd-surface);
  border:1px solid var(--kd-overlay-stroke);
  transform:scale(var(--kd-size));
}
.dot{transform:scale(var(--kd-dot-size))}

/* 还可直接使用：
--kd-background --kd-debug-surface --kd-text-secondary --kd-divider --kd-accent
--kd-key-idle --kd-key-pressed --kd-key-text-idle --kd-key-text-pressed
--kd-overlay-shell --kd-overlay-secondary
--kd-trajectory-panel --kd-trajectory-stroke --kd-trajectory-dot
--kd-width --kd-height --kd-density
--kd-opacity --kd-opacity-percent --kd-key-spacing --kd-idle-color --kd-press-color --kd-text-color
--kd-corner-scale --kd-ripple-strength --kd-key-style
--kd-mouse-sensitivity --kd-gamepad-sensitivity
*/
""");

        addSection(root, "6. 单键按压动效");
        addCode(root, """
KeyDisplay.on('key', e => {
  const k=e.detail;
  const el=document.querySelector(`[data-id="${k.id}"]`);
  if(!el)return;
  el.getAnimations().forEach(a=>a.cancel());
  el.animate(
    k.pressed
      ? [{transform:'scale(1)'},{transform:'scale(.88)'}]
      : [{transform:'scale(.88)'},{transform:'scale(1.04)'},{transform:'scale(1)'}],
    {duration:k.pressed?85:190,easing:'cubic-bezier(.2,.8,.2,1)',fill:'forwards'}
  );
});
""");

        addSection(root, "7. 键盘 / Space / 自定义键位");
        addCode(root, """
function renderKeys(s){
  if(!s.keys)return;
  root.innerHTML=s.keys.map(k=>`
    <div class="key ${k.pressed?'down':''}" data-id="${k.id}">
      <b>${k.label}</b>${k.cps?`<small>${k.cps} CPS</small>`:''}
    </div>`).join('');
}
KeyDisplay.on('update',e=>renderKeys(e.detail));
""");

        addSection(root, "8. 鼠标实时状态");
        addCode(root, """
KeyDisplay.on('mouse', e => {
  const m=e.detail;
  left.classList.toggle('down',m.left);
  right.classList.toggle('down',m.right);
  leftCps.textContent=m.leftCps+' CPS';
  rightCps.textContent=m.rightCps+' CPS';
});
""");

        addSection(root, "9. 鼠标轨迹：按移动力度推动，停止后回中");
        addCode(root, """
let x=0,y=0,vx=0,vy=0,raf=0;
KeyDisplay.on('pointer',e=>{
  const {dx,dy}=e.detail;
  vx+=Math.sign(dx)*Math.sqrt(Math.abs(dx))*0.72;
  vy+=Math.sign(dy)*Math.sqrt(Math.abs(dy))*0.72;
  if(!raf)raf=requestAnimationFrame(tick);
});
function tick(){
  vx+=(-x*.12-vx*.26); vy+=(-y*.12-vy*.26);
  x+=vx; y+=vy;
  dot.style.transform=`translate(${x}px,${y}px) scale(var(--kd-dot-size))`;
  if(Math.abs(x)+Math.abs(y)+Math.abs(vx)+Math.abs(vy)<.04){
    x=y=vx=vy=0;raf=0;return;
  }
  raf=requestAnimationFrame(tick);
}
""");

        addSection(root, "10. 鼠标轨迹左右键变色");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const s=e.detail;if(s.type!=='mouse-trajectory')return;
  const b=s.mouseButtons,c=s.config;
  if(b.left&&c.showTrajectoryLeftColor) dot.style.background=c.trajectoryLeftColor;
  else if(b.right&&c.showTrajectoryRightColor) dot.style.background=c.trajectoryRightColor;
  else dot.style.background=s.palette.trajectoryDot;
});
""");

        addSection(root, "11. 左右摇杆 + L3/R3");
        addCode(root, """
let lastL3=false,lastR3=false;
KeyDisplay.on('gamepad',e=>{
  const g=e.detail;
  const right=KeyDisplay.type==='gamepad-right-stick';
  const x=right?g.rx:g.lx,y=right?g.ry:g.ly;
  knob.style.translate=`${x*32}px ${y*32}px`;
  const down=right?g.buttons.r3:g.buttons.l3;
  const old=right?lastR3:lastL3;
  if(down&&!old) knob.animate(
    [{scale:'1'},{scale:'1.16'},{scale:'.97'},{scale:'1'}],
    {duration:170,easing:'cubic-bezier(.2,.8,.2,1)'}
  );
  if(right)lastR3=down;else lastL3=down;
});
""");

        addSection(root, "12. ABXY：直接使用语义字段");
        addCode(root, """
KeyDisplay.on('gamepad',e=>{
  const b=e.detail.buttons;
  A.classList.toggle('down',b.a);
  B.classList.toggle('down',b.b);
  X.classList.toggle('down',b.x);
  Y.classList.toggle('down',b.y);
});
// 布局：Y 上、X 左、B 右、A 下。
// 推荐使用 a/b/x/y，不要依赖底层 north/west 名称。
""");

        addSection(root, "13. L1/R1 CPS 与 L2/R2 压力");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const s=e.detail;if(!s.gamepad)return;
  const g=s.gamepad;
  if(s.type==='gamepad-left-shoulder'){
    l1.classList.toggle('down',g.buttons.l1);
    l1Cps.textContent=s.config.showShoulderCps?g.cps.l1+' CPS':'';
    if(s.config.showTriggerProgress) l2.style.setProperty('--pressure',g.lt);
  }
  if(s.type==='gamepad-right-shoulder'){
    r1.classList.toggle('down',g.buttons.r1);
    r1Cps.textContent=s.config.showShoulderCps?g.cps.r1+' CPS':'';
    if(s.config.showTriggerProgress) r2.style.setProperty('--pressure',g.rt);
  }
});
""");

        addSection(root, "14. Runtime：根据灵敏度和位置改变视觉");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const r=e.detail.runtime;
  hud.dataset.overclock=r.sensitivityEnabled?'on':'off';
  hud.style.setProperty('--x',r.positionXPercent+'%');
  hud.style.setProperty('--y',r.positionYPercent+'%');
  console.log('本次会话是否自动持久化',r.sessionPersistent); // false
});
""");

        addSection(root, "15. Palette：完全跟随应用主题");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const p=e.detail.palette;
  panel.style.background=p.overlayShell;
  panel.style.color=p.textPrimary;
  panel.style.borderColor=p.overlayStroke;
});
""");

        addSection(root, "16. SVG / Canvas 也可以直接使用");
        addCode(root, """
const canvas=document.querySelector('canvas');
const ctx=canvas.getContext('2d');
function draw(s){
  ctx.clearRect(0,0,canvas.width,canvas.height);
  ctx.fillStyle=s.palette.accent;
  ctx.beginPath();ctx.arc(canvas.width/2,canvas.height/2,12,0,Math.PI*2);ctx.fill();
}
KeyDisplay.on('update',e=>draw(e.detail));
""");

        addSection(root, "17. 最近按键也可完全重写");
        addCode(root, """
if(KeyDisplay.type==='key-prompt'){
  KeyDisplay.on('update',e=>{
    root.innerHTML=e.detail.keys.map(k=>`
      <div class="prompt ${k.pressed?'down':''}" data-id="${k.id}">
        <b>${k.label}</b>${k.cps?`<small>${k.cps} CPS</small>`:''}
      </div>`).join('');
  });
  KeyDisplay.on('key',e=>{
    const el=document.querySelector(`[data-id="${e.detail.id}"]`);
    if(el)el.animate([{scale:'.72'},{scale:'1.06'},{scale:'1'}],
      {duration:180,easing:'cubic-bezier(.2,.8,.2,1)'});
  });
}
""");

        addSection(root, "18. Settings：一次读取全部功能配置");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const s=e.detail.settings;
  console.log(s.keyboard.showSpace);
  console.log(s.mouseTrajectory.dotSizePercent);
  console.log(s.gamepad.leftStick.shape);
  console.log(s.gamepad.face.yCps);
  console.log(s.gamepad.leftShoulder.triggerProgress);
  console.log(s.sensitivity.mode,s.sensitivity.mousePercent);
});
// settings 是只读快照。HTML 改视觉，不直接修改 Android 权限或配置。
""");

        addSection(root, "19. Viewport：根据窗口尺寸自适应布局");
        addCode(root, """
KeyDisplay.on('update',e=>{
  const v=e.detail.viewport;
  root.classList.toggle('wide',v.aspectRatio>1.4);
  root.style.fontSize=Math.max(10,Math.min(18,v.dpWidth/12))+'px';
});
// viewport: width/height/dpWidth/dpHeight/density/densityDpi/aspectRatio/orientation
""");

        addSection(root, "20. 按键外观与间距");
        addCode(root, """
KeyDisplay.onState(s => {
  const c=s.config;
  root.style.opacity=String((c.opacityPercent??100)/100);
  root.style.setProperty('--gap',(c.spacingDp??0)+'px');
  root.dataset.style=c.keyStyle||'rounded';
  root.style.setProperty('--idle',c.idleColor||s.palette.keyIdle);
  root.style.setProperty('--pressed',c.pressColor||s.palette.keyPressed);
  root.style.setProperty('--text',c.textColor||s.palette.keyTextIdle);
  root.style.setProperty('--corner-scale',String((c.cornerScalePercent??100)/100));
  root.style.setProperty('--ripple-strength',String((c.rippleStrengthPercent??100)/100));
});
// 同样可以直接使用：
// --kd-opacity --kd-key-spacing --kd-idle-color --kd-press-color --kd-text-color
// --kd-corner-scale --kd-ripple-strength --kd-key-style
""");

        addSection(root, "21. 本地存储与手柄兼容状态");
        addCode(root, """
// localStorage / sessionStorage 已启用。所有显示窗口使用同一页面来源。
const key='my-layout-'+KeyDisplay.type;
localStorage.setItem(key,JSON.stringify({compact:true}));
const saved=JSON.parse(localStorage.getItem(key)||'null');

KeyDisplay.onState(s=>{
  const c=s.settings.gamepad.compatibility;
  console.log(c.mode,c.swapXY,c.swapAB,c.swapSticks,c.swapTriggers);
});
// HTML API 只读，不能直接修改应用设置。
""");

        addSection(root, "22. 高频事件与完整状态");
        addBody(root,
                "pointer 和 gamepad 属于高频事件。Android 端按显示帧合并，并只修补对应局部状态。"
                + "KeyDisplay.getState() 在事件回调中仍能读取最新 pointer/gamepad。"
                + "设置、主题、大小等低频变化才发送完整 keydisplay:update。"
                + "这样可以降低 JSON 构建、JavaScript 执行和 DOM 更新次数。");

        addSection(root, "23. 性能规则");
        addBody(root,
                "1. HTML 关闭时不会创建 WebView。\n"
                + "2. 鼠标与手柄高频数据按显示帧合并。\n"
                + "3. key / mouse / pointer / gamepad 用于局部更新，避免反复重建 DOM。\n"
                + "4. 动画优先 transform、opacity 和 Web Animations API。\n"
                + "5. 静止后停止 requestAnimationFrame，不保留空转循环。\n"
                + "6. 图片、字体和脚本建议内联；HTML 文件上限 4 MB，网络访问关闭。\n"
                + "7. 根据 KeyDisplay.type 只创建当前窗口需要的元素。\n"
                + "8. localStorage 适合保存低频视觉设置，不要在高频输入事件中反复写入。");

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }


    private void addTitle(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(22f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(0, 0, 0, dp(12));
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSection(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(16f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        params.bottomMargin = dp(7);
        root.addView(view, params);
    }

    private void addBody(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(UiPalette.textSecondary(this));
        view.setTextSize(13f);
        view.setLineSpacing(0f, 1.18f);
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addCode(LinearLayout root, String code) {
        TextView view = new TextView(this);
        view.setText(code.trim());
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(11.5f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(UiPalette.rounded(this, UiPalette.debugSurface(this), 10f));
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void applySystemBars() {
        boolean black = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        getWindow().setStatusBarColor(UiPalette.background(this));
        getWindow().setNavigationBarColor(UiPalette.background(this));
        int flags = 0;
        if (!black) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
