const {chromium}=require('playwright');
const esbuild=require('esbuild');
const fs=require('fs');const http=require('http');const assert=require('assert/strict');const path=require('path');
(async()=>{
 const bundle=await esbuild.build({entryPoints:[path.join(__dirname,'fixture.jsx')],bundle:true,write:false,jsx:'automatic',nodePaths:[path.join(__dirname,'node_modules')]});
 const script=fs.readFileSync(path.join(__dirname,'../../app/src/main/assets/tv_viewer.js'),'utf8');
 const html=`<html><head><style>body{margin:0;background:#162330;color:white;font:18px sans-serif}button,input{min-width:80px;min-height:40px;margin:5px}#pageRoot{inset:0}.cards{display:grid;grid-template-columns:300px 300px;gap:20px;margin:80px 60px}.cards>div{height:140px;background:#354d68;padding:12px}.shade{position:fixed;inset:0;background:#0009;z-index:50}.dialog{position:fixed;z-index:50;left:20%;top:15%;width:60%;padding:16px;background:#223b53}.inner{left:25%;top:25%;width:50%}.scroll{height:260px;overflow:auto}.row{display:flex;align-items:center;justify-content:space-between;height:70px}.select{background:#345;z-index:60;padding:15px}[role=option]{padding:12px}</style></head><body><div id='root'></div><script>${bundle.outputFiles[0].text}</script></body></html>`;
 const server=http.createServer((req,res)=>{if(req.url==='/api/config'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({camera_groups:{Home:{order:0,cameras:['bedroom']}}}));}else{res.setHeader('Content-Type','text/html');res.end(html);}}).listen(0,'127.0.0.1');
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const page=await browser.newPage({viewport:{width:1280,height:720}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const key=async k=>{const r=await page.evaluate(k=>window.PhylaxTV.key(k),k);await page.waitForTimeout(160);return r;};
 const selected=()=>page.evaluate(()=>document.activeElement.id||document.activeElement.dataset.camera||document.activeElement.textContent);
 try{
 await page.goto('http://127.0.0.1:'+server.address().port);await page.waitForSelector('[data-camera]');await page.evaluate(()=>history.replaceState({usr:{cameraGroup:'Home'},idx:0},''));await page.addScriptTag({content:script});await page.waitForTimeout(200);
 await page.locator('[data-camera=bedroom]').focus();await key('right');assert.equal(await selected(),'livingroom');await key('down');assert.equal(await selected(),'front');await key('left');assert.equal(await selected(),'front2');await key('up');assert.equal(await selected(),'bedroom');await key('ok');assert.equal(await page.evaluate(()=>window.clicks),1);console.log('PASS directional grid and one activation');
 await key('menu');await key('ok');await page.waitForSelector('#tv-dialog');await page.getByText('新增／編輯群組',{exact:true}).focus();await key('ok');await page.waitForSelector('#gear0');await page.locator('#gear0').focus();await key('ok');await page.waitForSelector('#stream');
 assert.equal(await page.evaluate(()=>window.PhylaxTV.debug().scope),'dialog');await page.locator('#stream').focus();await key('ok');await page.waitForSelector('[role=listbox]');await key('down');await key('ok');assert.equal(await page.locator('#stream').textContent(),'continuous');console.log('PASS group gear and real Radix select');
 await page.locator('#stream').focus();await key('ok');await key('back');assert.equal(await page.locator('[role=listbox]').count(),0);assert.equal(await page.locator('#stream').count(),1);assert.equal(await page.evaluate(()=>window.pageEscapes),0);console.log('PASS menu Back never reaches background shortcut');
 await key('back');assert.equal(await page.locator('#stream').count(),0);assert.equal(await selected(),'gear0');console.log('PASS child dialog Back restores exact gear');
 await page.locator('#gear8').focus();await key('down');const id=await selected();assert.ok(id==='gear9'||id==='groupCancel',id);assert.ok(await page.locator('.scroll').evaluate(el=>el.scrollTop>0));console.log('PASS off-screen list scroll');
 await key('back');assert.equal(await page.locator('#gear0').count(),0);assert.ok((await selected()).includes('群組'));console.log('PASS parent Back restores group entry');
 assert.deepEqual(errors,[]);await page.screenshot({path:path.join(__dirname,'tv-navigation.png')});console.log('ALL TV navigation checks passed');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
