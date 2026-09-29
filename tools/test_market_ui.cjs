const {chromium}=require('playwright');
const path=require('path');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1280,height:900}});
 const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 const calls=[];let posts=[{id:'test',owner:'seller',seller:'Venditore',title:'Carabina <script>bad()</script>',description:'Ottime condizioni',item:'Arma ×1',price:'5000 $',threads:{}}];
 page.on('console',async msg=>{
  if(!msg.text().startsWith('__NOMARKET__'))return;
  const [id,data]=msg.text().slice(12).split(':');const q=JSON.parse(Buffer.from(data,'base64').toString());calls.push(q);
  let r={ok:true,me:'buyer',now:Date.now(),meetings:[]};
  if(q.action==='create'){posts.push({id:'new',owner:'buyer',seller:'Tu',item:'Ferro ×32',threads:{},...q});r.posts=posts;}
  if(q.action==='list')Object.assign(r,{posts,total:posts.length,page:0});
  if(q.action==='view')r.post=posts.find(p=>p.id===q.post);
  if(q.action==='reply')posts[0].threads.buyer={buyer:'buyer',name:'Tu',messages:[{name:'Tu',text:q.text}]};
  if(q.action==='meet')posts[0].threads.buyer.meeting={id:'meeting',proposer:'buyer',accepted:false,dimension:'minecraft:overworld',x:10,y:64,z:20,expires:Date.now()+1800000};
  const payload=Buffer.from(JSON.stringify(r)).toString('base64');await page.evaluate(({id,payload})=>window.resolveMarket(Number(id),payload),{id,payload});
 });
 await page.goto('file://'+path.resolve('projects/nuovo-ordine-suite/market/src/main/resources/market/index.html'));
 await page.getByRole('button',{name:'Apri annuncio'}).waitFor();
 await page.getByRole('button',{name:'Apri annuncio'}).click();
 await page.getByPlaceholder('Scrivi una risposta…').fill('Propongo uno scambio');
 await page.getByRole('button',{name:'Invia risposta'}).click();
 await page.getByRole('button',{name:'Proponi incontro qui'}).click();
 await page.getByText(/In attesa di conferma/).waitFor();
 await page.screenshot({path:process.env.MARKET_SCREENSHOT||'/tmp/nuovoordine-market.png',fullPage:true});
 await page.getByRole('button',{name:'+ Pubblica annuncio'}).click();
 await page.locator('#title').fill('Ferro');await page.locator('#description').fill('32 lingotti');await page.locator('#price').fill('1000 $');await page.locator('#publish').click();
 await page.getByRole('heading',{name:'Ferro',exact:true}).waitFor();
 if(errors.length)throw Error(errors.join('\n'));
 for(const action of ['list','view','reply','meet','create'])if(!calls.some(c=>c.action===action))throw Error('Missing '+action);
 if(await page.evaluate(()=>typeof bad!=='undefined'))throw Error('HTML injection');
 console.log('Market UI passed: list, view, private reply, meeting proposal, create, HTML escaping; no page errors.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
