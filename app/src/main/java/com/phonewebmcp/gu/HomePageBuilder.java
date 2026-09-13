package com.phonewebmcp.gu;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 新标签页（主页）HTML 生成器。
 * 功能：问候语、搜索框（引擎选择 + 搜索按钮）、收藏网格（可管理/编辑/删除/新增）、
 *       快捷导航胶囊（可管理）、深色模式（跟随系统 + 手动循环切换）、引擎分组面板。
 * 与 Java 侧交互通过 NativeBridge JS 桥（MainActivity 注入）。
 */
public class HomePageBuilder {

    public static String build(String bookmarksJson, String servicesJson,
                               String engineTemplate, String engineName, String theme) {
        // 引擎数据 → JS 数组（分组）
        StringBuilder common = new StringBuilder();
        StringBuilder ai = new StringBuilder();
        for (String[] e : SearchEngines.LIST) {
            String item = "[" + jsStr(e[0]) + "," + jsStr(e[2]) + "]";
            if (e[1].equals("ai")) {
                if (ai.length() > 0) ai.append(",");
                ai.append(item);
            } else {
                if (common.length() > 0) common.append(",");
                common.append(item);
            }
        }

        String html = TEMPLATE
                .replace("__MARKS__", bookmarksJson == null ? "[]" : bookmarksJson)
                .replace("__SVCS__", servicesJson == null ? "[]" : servicesJson)
                .replace("__ENGINES_COMMON__", common.toString())
                .replace("__ENGINES_AI__", ai.toString())
                .replace("__CUR__", jsStr(engineTemplate))
                .replace("__CURNAME__", jsStr(engineName))
                .replace("__THEME__", theme == null ? "" : theme);

        String encoded = URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
        return "data:text/html;charset=utf-8," + encoded;
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    private static final String TEMPLATE = """
<!DOCTYPE html><html data-theme="__THEME__"><head><meta charset=utf-8><title>新标签页</title>
<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --bg1:#eef2ff;--bg2:#eff6ff;--card:#ffffff;--card-border:#eef2f7;
  --text:#1e293b;--muted:#64748b;--muted2:#94a3b8;
  --accent:#2563eb;--accent-soft:#eff6ff;--accent-border:#dbeafe;
  --input-bg:#ffffff;--bar:#e2e8f0;--shadow:rgba(30,41,59,.06);
  --danger:#dc2626;
}
[data-theme=dark]{
  --bg1:#141821;--bg2:#181e2a;--card:#1E222D;--card-border:#2a3040;
  --text:#E2E8F0;--muted:#94a3b8;--muted2:#64748b;
  --accent:#3b82f6;--accent-soft:#1e293b;--accent-border:#334155;
  --input-bg:#1E222D;--bar:#334155;--shadow:rgba(0,0,0,.35);
  --danger:#f87171;
}
@media (prefers-color-scheme: dark){
  :root:not([data-theme]){--bg1:#141821;--bg2:#181e2a;--card:#1E222D;--card-border:#2a3040;
    --text:#E2E8F0;--muted:#94a3b8;--muted2:#64748b;--accent:#3b82f6;
    --accent-soft:#1e293b;--accent-border:#334155;--input-bg:#1E222D;--bar:#334155;
    --shadow:rgba(0,0,0,.35);--danger:#f87171;}
}
body{font-family:system-ui,-apple-system,sans-serif;background:linear-gradient(160deg,var(--bg1) 0%,var(--bg2) 100%);min-height:100vh;padding:100px 18px 34px;color:var(--text);transition:background .3s}
.wrap{max-width:640px;margin:0 auto;text-align:center}
.theme{position:fixed;top:12px;right:14px;background:var(--card);border:1px solid var(--card-border);border-radius:50%;width:36px;height:36px;font-size:16px;cursor:pointer;box-shadow:0 1px 4px var(--shadow);z-index:5}
.greet{font-size:13px;color:var(--muted2);margin-bottom:4px}
.logo{font-size:32px;font-weight:700;letter-spacing:2px;margin-bottom:44px}
.logo span{color:var(--accent)}
.search{position:relative;max-width:600px;margin:0 auto;display:flex;gap:8px;align-items:center}
.box{position:relative;flex:1;min-width:0}
.box input{width:100%;padding:15px 100px 15px 20px;border-radius:28px;border:1px solid var(--bar);background:var(--input-bg);font-size:16px;outline:none;box-shadow:0 4px 16px var(--shadow);color:var(--text)}
.box input:focus{border-color:var(--accent);box-shadow:0 4px 20px rgba(37,99,235,.16)}
.go{width:52px;height:52px;flex-shrink:0;background:var(--input-bg);color:var(--accent);border:1px solid var(--bar);border-radius:26px;font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center}
.eng{position:absolute;right:6px;top:50%;transform:translateY(-50%);background:var(--accent-soft);color:var(--accent);border:1px solid var(--accent-border);border-radius:20px;padding:8px 13px;font-size:13px;cursor:pointer;display:flex;align-items:center;gap:4px;max-width:96px;z-index:2}
.eng b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.panel{display:none;position:fixed;left:0;right:0;top:0;bottom:0;background:rgba(15,23,42,.45);z-index:20;justify-content:center;align-items:flex-start;padding-top:10vh}
.panel.show{display:flex}
.sheet{background:var(--card);border-radius:20px;width:min(560px,92vw);max-height:68vh;overflow:auto;padding:18px 14px;box-shadow:0 20px 60px rgba(0,0,0,.25)}
.sec{font-size:12px;color:var(--muted2);margin:14px 6px 8px}
.grid2{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.en{display:flex;align-items:center;gap:8px;padding:10px 12px;border-radius:12px;cursor:pointer;border:1px solid transparent;color:var(--text)}
.en:hover{background:var(--accent-soft)}
.en.cur{background:var(--accent-soft);border-color:var(--accent-border);color:var(--accent)}
.en .dot{width:8px;height:8px;border-radius:50%;flex-shrink:0}
.dot.c{background:var(--accent)}.dot.a{background:#8b5cf6}.dot.s{background:#10b981}
.en .nm{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
h3{font-size:14px;color:var(--muted);margin:28px 0 4px;text-align:left;padding-left:8px;display:flex;align-items:center;justify-content:space-between;padding-right:8px}
.mgr{background:var(--accent-soft);color:var(--accent);border:1px solid var(--accent-border);border-radius:12px;padding:4px 10px;font-size:12px;cursor:pointer}
.mark{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-top:10px}
.site{display:flex;flex-direction:column;align-items:center;justify-content:center;background:var(--card);border:1px solid var(--card-border);border-radius:12px;padding:10px 2px;min-height:62px;min-width:0;overflow:hidden;text-decoration:none;box-shadow:0 1px 4px var(--shadow);position:relative}
.site .ic{width:30px;height:30px;border-radius:8px;margin:0 auto 5px;display:flex;align-items:center;justify-content:center;font-size:14px;color:#fff;font-weight:600;flex-shrink:0}
.site .nm{font-size:11px;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0;max-width:100%;width:100%;text-align:center;padding:0 4px}
.site.add{border:1px dashed var(--bar);background:transparent;color:var(--muted2)}
.site.add .ic{background:transparent;color:var(--muted2)}
.site .edit-btn{position:absolute;top:4px;right:4px;background:var(--accent-soft);border:1px solid var(--accent-border);border-radius:8px;width:20px;height:20px;font-size:11px;color:var(--accent);display:none;align-items:center;justify-content:center;cursor:pointer}
.mgr-mode .site .edit-btn{display:flex}
.svcs{display:flex;gap:8px;justify-content:center;flex-wrap:wrap;margin-top:10px}
.svc{background:var(--card);border:1px solid var(--card-border);border-radius:18px;padding:7px 14px;font-size:12px;color:var(--text);text-decoration:none;box-shadow:0 1px 3px var(--shadow);position:relative}
.svc.add{border:1px dashed var(--bar);background:transparent;color:var(--muted2)}
.foot{color:var(--muted2);font-size:11px;margin-top:26px}
.pop{position:fixed;z-index:30;background:var(--card);border:1px solid var(--card-border);border-radius:14px;box-shadow:0 12px 40px rgba(0,0,0,.25);overflow:hidden;min-width:150px}
.pop.hidden{display:none}
.pop .pi{display:flex;align-items:center;gap:8px;padding:12px 16px;font-size:14px;cursor:pointer;color:var(--text)}
.pop .pi:hover{background:var(--accent-soft)}
.pop .pi.danger{color:var(--danger)}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.35);z-index:25;display:flex;align-items:center;justify-content:center}
.mask.hidden{display:none}
.modal{background:var(--card);border-radius:18px;width:min(420px,88vw);padding:20px;box-shadow:0 20px 60px rgba(0,0,0,.3)}
.modal h4{font-size:15px;margin-bottom:14px;color:var(--text)}
.modal input{width:100%;padding:11px 14px;border-radius:12px;border:1px solid var(--bar);background:var(--input-bg);color:var(--text);font-size:14px;outline:none;margin-bottom:10px}
.modal input:focus{border-color:var(--accent)}
.mrow{display:flex;gap:10px;margin-top:6px}
.mrow button{flex:1;padding:11px;border-radius:12px;border:none;font-size:14px;cursor:pointer}
.btn-p{background:var(--accent);color:#fff}
.btn-n{background:var(--accent-soft);color:var(--muted)}
</style></head>
<body>
<div class=wrap>
  <button class=theme id=themeBtn onclick=toggleTheme()>🌓</button>
  <div class=greet id=greet></div>
  <div class=logo>古<span>月</span></div>
  <div class=search>
    <div class=box>
      <input id=q placeholder="搜索，或输入网址" onkeydown="if(event.key==='Enter')doSearch()">
      <button class=eng onclick=toggle()><b id=engName></b> ▾</button>
    </div>
    <button class=go onclick=doSearch()>🔍</button>
  </div>
  <div id=panel class=panel onclick="if(event.target===this)closePanel()">
    <div class=sheet>
      <div class=sec>✨ 常用搜索</div><div class=grid2 id=gCommon></div>
      <div class=sec>🤖 AI 搜索</div><div class=grid2 id=gAi></div>
      <div class=sec>🛠 本机服务</div><div class=grid2 id=gSvc></div>
    </div>
  </div>
  <h3><span>我的收藏</span><button class=mgr id=mgrBtn onclick=toggleMgr()>管理</button></h3>
  <div class="mark" id=marks></div>
  <h3>快捷导航</h3>
  <div class=svcs id=svcs></div>
  <div class=foot>古月 · WebView 调试浏览器 · 长按可编辑</div>
</div>
<div class="pop hidden" id=ctx>
  <div class=pi onclick=ctxEdit()>✏️ 编辑</div>
  <div class="pi danger" onclick=ctxDel()>🗑️ 删除</div>
  <div class=pi onclick=hideCtx()>取消</div>
</div>
<div class="mask hidden" id=mask>
  <div class=modal>
    <h4 id=modalTitle>编辑</h4>
    <input id=inName placeholder="名称">
    <input id=inUrl placeholder="网址，如 https://..." inputmode=url>
    <div class=mrow><button class=btn-p onclick=modalSave()>保存</button><button class=btn-n onclick=hideMask()>取消</button></div>
  </div>
</div>
<script>
const MARKS=__MARKS__,SVCS=__SVCS__;
const E=[__ENGINES_COMMON__],A=[__ENGINES_AI__],S=SVCS;
const CUR=__CUR__,CURNAME=__CURNAME__;
const COLORS=['#2563eb','#059669','#d97706','#dc2626','#7c3aed','#0891b2','#db2777','#4f46e5'];
const LIMIT=6;
let cur=CUR,curName=CURNAME,mgr=false,ctx=null,editing=null;
let theme='__THEME__',themeIx=theme==='dark'?1:theme==='light'?2:0;
const THEMES=['system','dark','light'],THEMEICONS=['🌓','🌙','☀️'];

/* ---- 主题 ---- */
function applyTheme(){document.documentElement.dataset.theme=theme==='system'?'':theme;document.getElementById('themeBtn').textContent=THEMEICONS[themeIx]}
function toggleTheme(){themeIx=(themeIx+1)%3;theme=THEMES[themeIx];applyTheme();try{NativeBridge.saveTheme(theme)}catch(e){}}

/* ---- 搜索 ---- */
function doSearch(){const q=document.getElementById('q').value.trim();if(!q)return;location.href=cur.replace('{q}',encodeURIComponent(q))}
function pick(u,n){cur=u;curName=n;document.getElementById('engName').textContent=n;try{NativeBridge.setEngine(u,n)}catch(e){}closePanel();renderEngines()}
function toggle(){document.getElementById('panel').classList.add('show')}
function closePanel(){document.getElementById('panel').classList.remove('show')}
function engItem(name,url,cls){const d=document.createElement('div');d.className='en'+(url===cur?' cur':'');d.innerHTML='<span class="dot '+cls+'"></span><span class="nm">'+name+'</span>';d.onclick=()=>pick(url,name);return d}
function renderEngines(){const gc=document.getElementById('gCommon'),ga=document.getElementById('gAi'),gs=document.getElementById('gSvc');gc.innerHTML='';ga.innerHTML='';gs.innerHTML='';E.forEach(e=>gc.appendChild(engItem(e[0],e[1],'c')));A.forEach(e=>ga.appendChild(engItem(e[0],e[1],'a')));S.forEach(e=>{const d=engItem(e[0],e[1],'s');d.onclick=()=>{location.href=e[1]};gs.appendChild(d)});document.getElementById('engName').textContent=curName}

/* ---- 收藏 ---- */
function renderMarks(){
  const m=document.getElementById('marks');
  m.innerHTML='';m.classList.toggle('mgr-mode',mgr);
  const list=mgr?MARKS:MARKS.slice(0,LIMIT);
  list.forEach((b,i)=>{
    const idx=MARKS.indexOf(b);
    const a=document.createElement('a');a.className='site';a.href=b.url;
    const ic=document.createElement('div');ic.className='ic';ic.style.background=COLORS[idx%COLORS.length];ic.textContent=(b.title||'?').charAt(0);
    const nm=document.createElement('div');nm.className='nm';nm.textContent=b.title||b.url;
    const eb=document.createElement('div');eb.className='edit-btn';eb.textContent='✎';
    eb.onclick=(ev)=>{ev.preventDefault();ev.stopPropagation();editItem('bookmark',idx)};
    a.appendChild(ic);a.appendChild(nm);a.appendChild(eb);
    hold(a,()=>openCtx('bookmark',idx));
    m.appendChild(a);
  });
  if(mgr){
    const ad=document.createElement('a');ad.className='site add';ad.href='javascript:void(0)';
    const ic=document.createElement('div');ic.className='ic';ic.style.background='transparent';ic.style.color='var(--muted2)';ic.textContent='＋';
    const nm=document.createElement('div');nm.className='nm';nm.textContent='新增收藏';
    ad.appendChild(ic);ad.appendChild(nm);ad.onclick=()=>openEdit('bookmark',null);
    m.appendChild(ad);
  }else{
    const add=document.createElement('a');add.className='site add';add.href='app://newtab';
    const ic2=document.createElement('div');ic2.className='ic';ic2.style.background='transparent';ic2.style.color='var(--muted2)';ic2.textContent='＋';
    const nm2=document.createElement('div');nm2.className='nm';nm2.textContent='新标签';
    add.appendChild(ic2);add.appendChild(nm2);
    m.appendChild(add);
    if(MARKS.length>LIMIT){
      const more=document.createElement('a');more.className='site add';more.href='javascript:void(0)';
      const ic3=document.createElement('div');ic3.className='ic';ic3.style.background='transparent';ic3.style.color='var(--muted2)';ic3.textContent='⋯';
      const nm3=document.createElement('div');nm3.className='nm';nm3.textContent='全部 '+MARKS.length+' 个';
      more.appendChild(ic3);more.appendChild(nm3);more.onclick=()=>{mgr=true;document.getElementById('mgrBtn').textContent='收起';renderMarks()};
      m.appendChild(more);
    }
  }
}
function toggleMgr(){mgr=!mgr;document.getElementById('mgrBtn').textContent=mgr?'收起':'管理';renderMarks()}

/* ---- 快捷导航 ---- */
function renderSvcs(){
  const sv=document.getElementById('svcs');sv.innerHTML='';
  SVCS.forEach((s,i)=>{
    const a=document.createElement('a');a.className='svc';a.href=s.url;
    a.innerHTML=(s.icon||'🔗')+' '+s.name;
    hold(a,()=>openCtx('service',i));
    sv.appendChild(a);
  });
  const ad=document.createElement('a');ad.className='svc add';ad.href='javascript:void(0)';
  ad.textContent='＋ 添加快捷';ad.onclick=()=>openEdit('service',null);
  sv.appendChild(ad);
}

/* ---- 长按 / 上下文菜单 ---- */
function hold(el,cb){let t=null;const st=e=>{t=setTimeout(()=>{t=null;cb(e)},600)};const cl=()=>{if(t){clearTimeout(t);t=null}};el.addEventListener('touchstart',st,{passive:true});el.addEventListener('touchend',cl);el.addEventListener('touchmove',cl);el.addEventListener('mousedown',st);el.addEventListener('mouseup',cl);el.addEventListener('mouseleave',cl)}
function openCtx(type,idx){ctx={type:type,idx:idx};document.getElementById('ctx').classList.remove('hidden')}
function hideCtx(){ctx=null;document.getElementById('ctx').classList.add('hidden')}
function ctxEdit(){const t=ctx;hideCtx();if(t)openEdit(t.type,t.idx)}
function ctxDel(){const t=ctx;hideCtx();if(t){
  if(t.type==='bookmark'){MARKS.splice(t.idx,1);try{NativeBridge.saveBookmarks(JSON.stringify(MARKS))}catch(e){}}
  else{SVCS.splice(t.idx,1);try{NativeBridge.saveServices(JSON.stringify(SVCS))}catch(e){}}
  reloadHome();}}

/* ---- 编辑弹窗 ---- */
function openEdit(type,idx){
  editing={type:type,idx:idx};
  document.getElementById('modalTitle').textContent=(idx===null?'新增':'编辑')+(type==='bookmark'?'收藏':'快捷导航');
  document.getElementById('inName').value=idx===null?'':(type==='bookmark'?MARKS[idx].title||'':SVCS[idx].name||'');
  document.getElementById('inUrl').value=idx===null?'':(type==='bookmark'?MARKS[idx].url||'':SVCS[idx].url||'');
  document.getElementById('mask').classList.remove('hidden');
  document.getElementById('inName').focus();
}
function hideMask(){editing=null;document.getElementById('mask').classList.add('hidden')}
function modalSave(){
  const name=document.getElementById('inName').value.trim();
  const url=document.getElementById('inUrl').value.trim();
  if(!url){document.getElementById('inUrl').focus();return}
  if(editing.type==='bookmark'){
    if(editing.idx===null)MARKS.push({title:name||url,url:url});else{MARKS[editing.idx].title=name||MARKS[editing.idx].url;MARKS[editing.idx].url=url;}
    try{NativeBridge.saveBookmarks(JSON.stringify(MARKS))}catch(e){}
  }else{
    if(editing.idx===null)SVCS.push({name:name||url,url:url,icon:'🔗'});else{SVCS[editing.idx].name=name||SVCS[editing.idx].url;SVCS[editing.idx].url=url;}
    try{NativeBridge.saveServices(JSON.stringify(SVCS))}catch(e){}
  }
  reloadHome();
}
function reloadHome(){try{NativeBridge.reloadHome()}catch(e){location.reload()}}

/* ---- 问候 ---- */
function greet(){const h=new Date().getHours();const g=h<5?'夜深了':h<9?'早上好':h<12?'上午好':h<14?'中午好':h<18?'下午好':'晚上好';document.getElementById('greet').textContent=g+' · '+['周日','周一','周二','周三','周四','周五','周六'][new Date().getDay()]}

applyTheme();renderEngines();greet();renderMarks();renderSvcs();
</script></body></html>
""";
}
