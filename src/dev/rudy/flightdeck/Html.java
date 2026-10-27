package dev.rudy.flightdeck;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** Self-contained report. Untrusted values enter the DOM through textContent only. */
final class Html {
  private static final String SCRIPT = """
      'use strict';
      const data = JSON.parse(document.getElementById('data').textContent);
      const compare = data.schema === 'rudy-flightdeck-comparison/v1';
      const el = (tag, text, cls) => { const node = document.createElement(tag); if (text !== undefined) node.textContent = String(text); if (cls) node.className = cls; return node; };
      const number = n => n == null ? '—' : Number(n).toLocaleString('en-US', {maximumFractionDigits: 3});
      const section = (title, subtitle) => { const box = el('section'); box.append(el('h2',title),el('p',subtitle,'muted')); document.querySelector('main').append(box); return box; };
      const table = (box, columns, rows) => { const wrap = el('div',undefined,'table-wrap'), t=el('table'), head=el('thead'), h=el('tr'); columns.forEach(c=>h.append(el('th',c))); head.append(h); t.append(head); const body=el('tbody'); rows.forEach(row=>{const tr=el('tr'); row.forEach(cell=>tr.append(el('td',cell))); body.append(tr);}); t.append(body); wrap.append(t); box.append(wrap); };
      const summary = compare ? data.candidate : data.summary;
      document.querySelector('#subtitle').textContent = compare ? 'Recording comparison · sample mix and observed rates' : 'Offline JVM recording investigation';
      document.querySelector('#source').textContent = summary.source + ' / ' + summary.sha256;
      const cards = el('div',undefined,'cards');
      for (const [label,value] of [['Observed events',summary.eventsRead],['Recognized events',summary.recognizedEvents],['Observed seconds',summary.observedSeconds],['GC pause union / ms',summary.gcPauseUnionMs]]) {
        const card=el('div',undefined,'card'); card.append(el('span',label),el('strong',number(value))); cards.append(card);
      }
      document.querySelector('main').append(cards);
      const leads=section(compare?'New investigation leads':'Investigation leads','Thresholds are review prompts. They do not establish a vulnerability or a workload regression.');
      const findings=compare?data.newWarnings:data.findings;
      if(!findings.length) leads.append(el('p','No configured warning thresholds were reached. Capture completeness still matters.'));
      findings.forEach(f=>{const card=el('article',undefined,'finding '+f.level); card.append(el('span',f.id,'mono'),el('h3',f.title),el('p',f.evidence)); leads.append(card);});
      if(compare) {
        const metrics=section('Normalized comparison','A metric is incomparable when either recording has no corresponding events or no positive observed span. Match recorder settings and workloads.');
        table(metrics,['Event kind','Comparable','Before / s','After / s','Change / s'],data.metrics.map(m=>[m.kind,m.comparable?'yes':'unknown',number(m.baseline),number(m.candidate),number(m.delta)]));
        const stacks=section('CPU sample-mix changes','Percentage points refer to all observed Java execution samples in each recording, including samples without retained stacks.');
        table(stacks,['Stack (root → leaf)','Baseline %','Candidate %','Change / pp'],data.cpuStackChanges.map(s=>[s.frames.join(' → '),number(s.baselinePercent),number(s.candidatePercent),number(s.deltaPercentagePoints)]));
      } else {
        const metrics=section('Recorded event metrics','Durations sum across threads and can overlap. P95 upper is an approximate histogram bound, not an exact quantile. Allocation bytes are sampling weights.');
        table(metrics,['Kind','Count','Summed ms','Max ms','P95 upper / ms','Recorded / weighted bytes'],Object.entries(data.metrics).filter(([,s])=>s.count).map(([k,s])=>[k,number(s.count),number(s.totalMs),number(s.maxMs),number(s.p95UpperMs),number(s.bytes)]));
        const stacks=section('Weighted CPU stacks','Root-first weighted stack view for the exported top stacks. Click a frame to focus; Reset restores the full displayed sample set. This is a sample distribution, not a time axis.');
        const reset=el('button','Reset stack focus'); stacks.append(reset);
        const chart=el('div',undefined,'stack-chart'); stacks.append(chart);
        const root={name:'All displayed samples',weight:0,children:new Map()};
        (data.stackProfiles.CPU||[]).forEach(s=>{let node=root; node.weight+=s.weight; s.frames.forEach(frame=>{if(!node.children.has(frame))node.children.set(frame,{name:frame,weight:0,children:new Map()});node=node.children.get(frame);node.weight+=s.weight;});});
        function draw(focus) {
          chart.replaceChildren();
          if(!focus.weight){chart.append(el('p','No Java execution samples with recorded stacks are available.'));return;}
          const ns='http://www.w3.org/2000/svg', svg=document.createElementNS(ns,'svg'); let maxDepth=0;
          function frame(node,x,width,depth){maxDepth=Math.max(maxDepth,depth);if(width<1)return;const group=document.createElementNS(ns,'g'),rect=document.createElementNS(ns,'rect'),label=document.createElementNS(ns,'text'),title=document.createElementNS(ns,'title');rect.setAttribute('x',x);rect.setAttribute('y',depth*27);rect.setAttribute('width',Math.max(0,width-1));rect.setAttribute('height',25);rect.setAttribute('rx',3);rect.setAttribute('fill',`hsl(${155+depth*3} 60% ${Math.max(24,58-depth*2)}%)`);title.textContent=node.name+' / '+node.weight+' samples';group.append(rect,title);if(width>55){label.setAttribute('x',x+5);label.setAttribute('y',depth*27+17);label.textContent=node.name.slice(0,Math.floor(width/7)-1);group.append(label);}group.addEventListener('click',()=>draw(node));svg.append(group);let offset=x;[...node.children.values()].sort((a,b)=>b.weight-a.weight||a.name.localeCompare(b.name)).forEach(child=>{const w=width*child.weight/node.weight;frame(child,offset,w,depth+1);offset+=w;});}
          frame(focus,0,1100,0);svg.setAttribute('viewBox',`0 0 1100 ${(maxDepth+1)*27}`);svg.setAttribute('role','img');svg.setAttribute('aria-label','Weighted Java execution sample stacks');chart.append(svg);
        }
        reset.addEventListener('click',()=>draw(root)); draw(root);
        const timeline=section('Observed event timeline','Up to 120 UTC buckets. Event counts are assigned to start times; durations are not spread across buckets.');
        if(data.timeline.length) {
          const first=data.timeline[0].epochSecond,last=data.timeline.at(-1).epochSecond,step=Math.max(1,Math.ceil((last-first+1)/120)), bins=Array.from({length:Math.ceil((last-first+1)/step)},()=>0);
          data.timeline.forEach(t=>{bins[Math.floor((t.epochSecond-first)/step)]+=Object.values(t.metrics).reduce((n,s)=>n+s.count,0);});
          const max=Math.max(1,...bins), bars=el('div',undefined,'timeline');bins.forEach((count,i)=>{const bar=el('div',undefined,'bar');bar.style.height=(count/max*100)+'%';bar.title=new Date((first+i*step)*1000).toISOString()+' / '+count+' recognized events';bars.append(bar);});timeline.append(bars,el('p',step+' second buckets; hover for timestamp/count.','muted'));
        }
        const contention=section('Historical monitor-owner edges','These edges describe past blocked acquisitions and their recorded previous owner. They are not a simultaneous deadlock graph.');
        table(contention,['Waiter','Previous owner','Monitor class','Acquisitions','Summed blocked / ms','Max / ms'],data.monitorEdges.map(e=>[e.waiter,e.owner,e.monitorClass,number(e.stats.count),number(e.stats.totalMs),number(e.stats.maxMs)]));
        const threads=section('Threads by recorded wait','Summed wait includes monitor, park, file and socket events. Thread names are omitted unless explicitly requested.');
        table(threads,['Thread','Recognized events','Summed wait / ms'],data.threads.map(t=>[t.thread,number(t.recordedEvents),number(t.summedWaitMs)]));
        const resources=section('Resource evidence','Search local evidence. Default paths/endpoints are stable SHA-256 tokens; symbols remain visible.');
        const filter=el('input');filter.placeholder='Filter event kind or resource';filter.setAttribute('aria-label','Filter resource evidence');resources.append(filter);
        const resourceTable=el('div');resources.append(resourceTable);
        function resourceRows(){resourceTable.replaceChildren();const query=filter.value.toLowerCase();table(resourceTable,['Kind','Resource / class','Count','Summed / ms','Max / ms','Bytes'],data.resources.filter(r=>(r.kind+' '+r.resource).toLowerCase().includes(query)).map(r=>[r.kind,r.resource,number(r.stats.count),number(r.stats.totalMs),number(r.stats.maxMs),number(r.stats.bytes)]));}
        filter.addEventListener('input',resourceRows);resourceRows();
        const coverage=section('Capture coverage','Observed event inventory. Zero events may mean disabled recording, filtering, or no observation.');
        table(coverage,['Event type','Observed count'],Object.entries(data.coverage.eventsByType).map(([name,count])=>[name,number(count)]));
        coverage.append(el('p','Recorded sampled stacks: '+number(data.coverage.sampledStacksRecorded)+' / truncated: '+number(data.coverage.sampledStacksTruncated)+' / data-loss events: '+number(data.coverage.dataLossEvents)));
      }
      const notes=section('Interpretation boundaries','Keep the recording configuration and workload alongside this report.');
      const list=el('ul');data.caveats.forEach(note=>list.append(el('li',note)));notes.append(list);
      document.querySelector('#status').textContent=summary.complete?'COMPLETE READ':'JFR REPORTS DATA LOSS';
      """;
  private static final String STYLE = """
      :root{color-scheme:dark;--bg:#080e15;--panel:#101b28;--line:#26384a;--muted:#9db2c8;--teal:#55e0b6;--text:#edf4fc}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 system-ui,sans-serif}header,main,footer{max-width:1250px;margin:auto;padding:36px 26px}header{border-bottom:1px solid var(--line);background:radial-gradient(ellipse at 90% 0%,#16483e70,transparent 55%)}.eyebrow,.mono{font:12px/1.6 ui-monospace,monospace;color:var(--teal);letter-spacing:.08em}h1{font-size:clamp(38px,7vw,76px);line-height:1;margin:18px 0;letter-spacing:-.06em}#subtitle{font-size:22px;color:var(--muted)}#source{font:12px/1.6 ui-monospace,monospace;overflow-wrap:anywhere;color:var(--muted)}#status{display:inline-block;border:1px solid var(--teal);border-radius:30px;padding:5px 13px;font:11px/1.8 ui-monospace,monospace;color:var(--teal)}main{padding-top:22px}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.card,section{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:22px;margin-bottom:20px}.card span{display:block;color:var(--muted);font-size:12px}.card strong{display:block;font-size:29px;font-variant-numeric:tabular-nums}h2{font-size:24px;line-height:1.2;margin:0 0 12px}h3{font-size:17px;margin:4px 0}.muted,section>p{color:var(--muted)}.finding{border-left:3px solid var(--teal);padding:13px 18px;background:#0a1420;margin-top:12px}.warning{border-left-color:#f6c56b}.finding p{margin:0;color:var(--muted)}.table-wrap{max-width:100%;overflow:auto}table{width:100%;border-collapse:collapse;font-size:12px}th{text-align:left;color:var(--teal);font-size:10px;letter-spacing:.07em;text-transform:uppercase}td,th{padding:12px 10px;border-bottom:1px solid var(--line);vertical-align:top}td{overflow-wrap:anywhere;max-width:570px}button,input{background:#0a1420;color:var(--text);border:1px solid var(--line);border-radius:7px;padding:10px 13px;font:inherit}button{cursor:pointer;margin-bottom:14px}button:hover{border-color:var(--teal)}input{width:100%;margin:8px 0 18px}.stack-chart{max-height:460px;overflow:auto}.stack-chart svg{display:block;width:100%;min-width:750px}.stack-chart text{font:11px ui-monospace,monospace;fill:#061710;pointer-events:none}.stack-chart g{cursor:pointer}.timeline{height:150px;display:flex;align-items:end;gap:2px;border-bottom:1px solid var(--line);padding:0 3px}.bar{flex:1;min-height:1px;background:var(--teal);border-radius:3px 3px 0 0}li{margin:7px 0;color:var(--muted)}footer{color:var(--muted);font-size:12px}a{color:var(--teal)}@media(max-width:700px){.cards{grid-template-columns:1fr 1fr}header,main,footer{padding:24px 14px}section{padding:16px}.card{padding:14px}}@media print{body{background:white;color:#111}section,.card{background:white;break-inside:avoid}button,input{display:none}.muted,section>p,li{color:#444}}
      """;
  static String render(Object data, boolean comparison) {
    try {
      String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(SCRIPT.getBytes(StandardCharsets.UTF_8)));
      String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
          + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'sha256-" + hash + "'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
          + "<title>Rudy Flightdeck — " + (comparison ? "comparison" : "recording investigation") + "</title><style>" + STYLE + "</style></head><body>"
          + "<header><div class=\"eyebrow\">RUDY SYSTEMS / JVM OBSERVABILITY</div><h1>Rudy Flightdeck</h1><p id=\"subtitle\"></p><p id=\"source\"></p><span id=\"status\"></span></header><main></main>"
          + "<footer>Rudy Flightdeck " + Core.VERSION + " · Self-contained report · No external scripts or uploads.</footer><script id=\"data\" type=\"application/json\">" + Json.write(data) + "</script><script>" + SCRIPT + "</script></body></html>";
      if (html.length() > Json.MAX_CHARS) throw new IllegalArgumentException("limit: HTML report exceeds 16 Mi characters");
      return html;
    } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
  private Html() {}
}
