const page = await figma.getNodeByIdAsync('22:30');
await figma.setCurrentPageAsync(page);
const created = [], mutated = [], track = n => { created.push(n.id); return n; };
const [white, grouped, ink, secondary, separator, accent, handle, nativeRows, bodyStyle] = await Promise.all([
  figma.variables.importVariableByKeyAsync('0c1a82745bdff17e14d0d78142c756a4d97a74eb'),
  figma.variables.importVariableByKeyAsync('a90ed703166e4696edc327f717296a29491d2124'),
  figma.variables.getVariableByIdAsync('VariableID:4:31'), figma.variables.getVariableByIdAsync('VariableID:4:32'),
  figma.variables.getVariableByIdAsync('VariableID:4:33'), figma.variables.getVariableByIdAsync('VariableID:4:34'),
  figma.variables.getVariableByIdAsync('VariableID:4:35'),
  figma.importComponentSetByKeyAsync('da6c778eb113a68c7fd6f3828741ab33c3ec8f12'),
  figma.importStyleByKeyAsync('5e51bb075a2e98b7f8345cbb49bcf155a767717f')
]);
await Promise.all(['SF Pro','Noto Sans SC','Roboto'].flatMap(family => ['Regular','Medium'].map(style => figma.loadFontAsync({family,style}))));
const tokens = {white,grouped,ink,secondary,separator,accent,handle};
const paint = token => [figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:1,g:1,b:1}}, 'color', typeof token==='string'?tokens[token]:token)];
const solid = (hex,opacity=1) => [{type:'SOLID',color:figma.util.rgb(hex),opacity}];
function flow(parent,name,direction='VERTICAL',width=null,gap=0) {
  const n=track(figma.createAutoLayout(direction));n.name=name;n.fills=[];n.itemSpacing=gap;parent.appendChild(n);
  if(width!==null){n.resize(width,1);n.primaryAxisSizingMode=direction==='VERTICAL'?'AUTO':'FIXED';n.counterAxisSizingMode=direction==='VERTICAL'?'FIXED':'AUTO';}return n;
}
function fixed(parent,name,w,h,direction='HORIZONTAL') {const n=flow(parent,name,direction,w);n.resize(w,h);n.primaryAxisSizingMode='FIXED';n.counterAxisSizingMode='FIXED';n.counterAxisAlignItems='CENTER';return n;}
function text(parent,value,size=17,color='ink',weight='Regular') {
  const n=track(figma.createText());n.name=value;n.fontName={family:/[^\x00-\x7F]/.test(value)?'Noto Sans SC':'SF Pro',style:weight};n.fontSize=size;
  n.lineHeight={unit:'PIXELS',value:Math.round(size*1.4)};n.characters=value;n.fills=paint(color);parent.appendChild(n);return n;
}
function rect(parent,name,w,h,fill,radius=0) {const n=track(figma.createRectangle());n.name=name;n.resize(w,h);n.fills=fill;n.cornerRadius=radius;parent.appendChild(n);return n;}
function glyph(parent,name,path,size=18,color='#8E8E93') {const n=track(figma.createNodeFromSvg(`<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="${path}" stroke="${color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`));n.name=name;n.resize(size,size);parent.appendChild(n);created.push(...n.findAll(()=>true).map(c=>c.id));return n;}
function chevron(parent){return glyph(parent,'Chevron','M9 5L16 12L9 19');}
function circleButton(parent,name,kind='close') {const n=fixed(parent,name,32,32);n.primaryAxisAlignItems='CENTER';n.fills=solid('#E6E6EB');n.cornerRadius=16;glyph(n,name,kind==='close'?'M7 7L17 17M17 7L7 17':'M14.5 5L7.5 12L14.5 19',18,'#747880');return n;}
function line(parent,width,inset=16){const box=fixed(parent,'Inset separator',width,.5);box.paddingLeft=inset;rect(box,'Separator',width-inset,.5,paint('separator'));}
const iconIds={ios:{calendar:'22:31',reminders:'22:33',location:'22:35',photos:'22:37',music:'22:39',health:'22:41',alarms:'22:43'},android:{calendar:'22:55',reminders:'22:69',location:'22:92',photos:'22:108',music:'22:117',alarms:'22:132'}};
const icons={ios:{},android:{}};
for(const platform of Object.keys(iconIds))for(const [key,id]of Object.entries(iconIds[platform]))icons[platform][key]=await figma.getNodeByIdAsync(id);
function appIcon(parent,platform,key,size=44){const n=track(icons[platform][key].createInstance());parent.appendChild(n);n.rescale(size/256);return n;}
const component=track(figma.createComponent());component.name='Connector status / Capability row';component.description='两端共用的能力状态行。Logo 按平台替换；状态只读，点击进入同一个 Bottom Sheet 的详情。';
component.layoutMode='HORIZONTAL';component.resize(362,66);component.primaryAxisSizingMode='FIXED';component.counterAxisSizingMode='FIXED';component.counterAxisAlignItems='CENTER';component.paddingLeft=16;component.paddingRight=14;component.itemSpacing=12;component.fills=paint('white');component.x=1600;component.y=800;
const props={title:component.addComponentProperty('Title','TEXT','连接器'),count:component.addComponentProperty('Capabilities','TEXT','2 项能力'),status:component.addComponentProperty('Status','TEXT','已允许'),icon:component.addComponentProperty('Logo','INSTANCE_SWAP',icons.ios.calendar.id)};
const logo=appIcon(component,'ios','calendar');logo.componentPropertyReferences={mainComponent:props.icon};const label=flow(component,'Name and count','VERTICAL',null,2);label.layoutSizingHorizontal='FILL';
text(label,'连接器').componentPropertyReferences={characters:props.title};text(label,'2 项能力',12,'secondary').componentPropertyReferences={characters:props.count};text(component,'已允许',12,'secondary').componentPropertyReferences={characters:props.status};chevron(component);
const board=flow(page,'Whaleness / 连接器能力面板 / 双端设计','VERTICAL',1366,32);board.x=80;board.y=100;board.paddingTop=40;board.paddingBottom=40;board.paddingLeft=48;board.paddingRight=48;board.fills=solid('#E9EDF2');board.cornerRadius=28;
const intro=flow(board,'Design header','VERTICAL',1270,6);text(intro,'WHALENESS  /  CONNECTOR STATUS',12,'secondary','Medium');text(intro,'一个入口，查看网关已登记的能力',28,'ink','Medium');text(intro,'状态入口 → 连接器列表 → 能力详情',15,'secondary');
const screenGroups={},screens=[],prototype=[];
const data={ios:[['calendar','日历',2,'主机未允许'],['reminders','提醒事项',2,'已允许'],['location','位置',11,'已允许'],['photos','照片',1,'手机未开启'],['music','音乐',8,'已允许'],['health','健康',1,'手机未开启'],['alarms','闹钟',3,'手机未开启']],android:[['calendar','日历',2,'主机未允许'],['reminders','提醒事项',2,'已允许'],['location','位置',9,'已允许'],['photos','照片',1,'手机未开启'],['music','音乐',7,'已允许'],['alarms','闹钟',1,'手机未开启']]};
const totals={ios:28,android:22},count={ios:7,android:6};
function base(parent,platform,state){
  const screen=track(figma.createFrame());screen.name=platform+' / '+state;screen.resize(402,874);screen.cornerRadius=40;screen.clipsContent=true;screen.fills=paint('grouped');parent.appendChild(screen);screens.push(screen.id);
  const body=flow(screen,'状态页内容','VERTICAL',402,18);body.paddingLeft=20;body.paddingRight=20;body.paddingTop=10;
  const status=fixed(body,'系统状态栏',362,40);status.primaryAxisAlignItems='SPACE_BETWEEN';text(status,'14:45',15,'ink','Medium');text(status,'5G  ▰',13,'ink','Medium');
  if(platform==='ios'){const island=rect(screen,'Dynamic Island',112,28,solid('#090909'),15);island.x=145;island.y=10;}else{const camera=rect(screen,'Android camera',16,16,solid('#101114'),8);camera.x=193;camera.y=14;}
  const nav=fixed(body,'导航栏',362,48);const back=circleButton(nav,'返回','back');back.resize(44,44);back.cornerRadius=22;back.fills=paint('white');const heading=text(nav,'连接器状态',19,'ink','Medium');heading.layoutSizingHorizontal='FILL';heading.textAlignHorizontal='CENTER';rect(nav,'Title balancing space',44,44,[]);
  const section=flow(body,'网关注册','VERTICAL',362,10);text(section,'网关注册',14,'secondary');const group=flow(section,'注册状态卡片','VERTICAL',362);group.cornerRadius=22;group.clipsContent=true;group.fills=paint('white');
  const first=fixed(group,'注册连接状态',362,54);first.paddingLeft=18;first.itemSpacing=9;rect(first,'在线',8,8,solid('#21C45D'),4);text(first,'网关已注册',17);
  for(const [key,value]of [['网关已确认',totals[platform]+' 项能力'],['最近确认','14:45']]){line(group,362,18);const row=fixed(group,key,362,52);row.paddingLeft=18;row.paddingRight=18;const t=text(row,key,16);t.layoutSizingHorizontal='FILL';text(row,value,15,'secondary');}
  const entry=fixed(body,'网关已登记的能力 · 打开面板',362,76);entry.paddingLeft=16;entry.paddingRight=14;entry.itemSpacing=12;entry.fills=paint('white');entry.cornerRadius=22;
  const mark=fixed(entry,'能力入口图标',40,40);mark.primaryAxisAlignItems='CENTER';mark.fills=solid('#EDF3FF');mark.cornerRadius=11;glyph(mark,'连接器','M4.8 3H8.2A1.8 1.8 0 0 1 10 4.8V8.2A1.8 1.8 0 0 1 8.2 10H4.8A1.8 1.8 0 0 1 3 8.2V4.8A1.8 1.8 0 0 1 4.8 3ZM15.8 3H19.2A1.8 1.8 0 0 1 21 4.8V8.2A1.8 1.8 0 0 1 19.2 10H15.8A1.8 1.8 0 0 1 14 8.2V4.8A1.8 1.8 0 0 1 15.8 3ZM4.8 14H8.2A1.8 1.8 0 0 1 10 15.8V19.2A1.8 1.8 0 0 1 8.2 21H4.8A1.8 1.8 0 0 1 3 19.2V15.8A1.8 1.8 0 0 1 4.8 14ZM14 17.5L16.5 20L21 14.5',24,'#2E6BE6');const titles=flow(entry,'入口文字','VERTICAL',null,3);titles.layoutSizingHorizontal='FILL';text(titles,'网关已登记的能力',16);text(titles,count[platform]+' 个连接器',12,'secondary');chevron(entry);
  const actions=fixed(body,'刷新与重新注册',362,54);actions.fills=paint('white');actions.cornerRadius=20;actions.paddingLeft=18;actions.paddingRight=18;actions.primaryAxisAlignItems='SPACE_BETWEEN';text(actions,'刷新状态',15,'accent');text(actions,'重新注册',15,'accent');
  const home=rect(screen,'系统手势指示条',platform==='ios'?134:108,5,solid('#333338'),3);home.x=(402-home.width)/2;home.y=859;
  return {screen,entry};
}
function sheetBase(screen,title,subtitle,back=false){
  const scrim=rect(screen,'背景遮罩',402,874,solid('#000000',.24));
  const sheet=flow(screen,'Bottom Sheet','VERTICAL',402);sheet.fills=paint('grouped');sheet.topLeftRadius=32;sheet.topRightRadius=32;sheet.bottomLeftRadius=0;sheet.bottomRightRadius=0;sheet.paddingTop=10;sheet.paddingLeft=20;sheet.paddingRight=20;
  const grab=fixed(sheet,'拖动条区域',362,21);grab.primaryAxisAlignItems='CENTER';grab.counterAxisAlignItems='MIN';rect(grab,'拖动条',36,5,paint('handle'),3);
  const header=fixed(sheet,'面板标题栏',362,32);header.itemSpacing=10;const backButton=back?circleButton(header,'返回能力列表','back'):null;const t=text(header,title,21,'ink','Medium');t.layoutSizingHorizontal='FILL';const close=circleButton(header,'关闭面板');
  if(subtitle){const sub=fixed(sheet,'平台与登记数量',362,43);sub.counterAxisAlignItems='MIN';sub.paddingTop=6;text(sub,subtitle,13,'secondary');}
  return {sheet,scrim,close,backButton};
}
function safe(parent){const n=fixed(parent,'底部安全区',362,42);n.primaryAxisAlignItems='CENTER';n.counterAxisAlignItems='MAX';n.paddingBottom=10;rect(n,'Home indicator',134,5,solid('#333338'),3);}
async function nativeToolRow(parent,title){const row=fixed(parent,title,362,52);row.paddingRight=16;const variant=nativeRows.children.find(n=>n.name==='Height=Regular');const i=track(variant.createInstance());row.appendChild(i);i.resize(328,52);const defs=nativeRows.componentPropertyDefinitions;const key=prefix=>Object.keys(defs).find(k=>k.startsWith(prefix+'#'));i.setProperties({[key('Title')]:title,[key('Show Image')]:false,[key('Show Subtitle')]:false,[key('Show Trailing')]:false});for(const t of i.findAllWithCriteria({types:['TEXT']})){if(t.name==='Title'){t.fontName={family:'Noto Sans SC',style:'Regular'};t.fontSize=15;mutated.push(t.id);}}glyph(row,'已登记','M5 12L10 17L19 7',17,'#21A75A');return row;}
for(const platform of ['ios','android']){
  const section=flow(board,platform+' / 流程预览','VERTICAL',1270,16);text(section,platform==='ios'?'iOS · 苹果系统应用图标':'Android · 本机能力图标',19,'ink','Medium');const columns=flow(section,'三个交互状态','HORIZONTAL',1270,32);const group=[];
  for(const [index,name]of ['状态入口','连接器列表','能力详情'].entries()){const c=flow(columns,name,'VERTICAL',402,12);text(c,'0'+(index+1)+' · '+name,13,'secondary','Medium');group.push(base(c,platform,name));}
  screenGroups[platform]=group;const list=sheetBase(group[1].screen,'已登记的能力',(platform==='ios'?'iOS':'Android')+' · '+count[platform]+' 个连接器 · '+totals[platform]+' 项能力');const listBody=flow(list.sheet,'连接器列表','VERTICAL',362);listBody.fills=paint('white');listBody.cornerRadius=20;listBody.clipsContent=true;
  for(const [index,[key,name,tools,status]]of data[platform].entries()){if(index)line(listBody,362,72);const i=track(component.createInstance());listBody.appendChild(i);i.setProperties({[props.title]:name,[props.count]:tools+' 项能力',[props.status]:status,[props.icon]:icons[platform][key].id});if(key==='music')prototype.push({from:i.id,to:group[2].screen.id});}
  safe(list.sheet);list.sheet.x=0;list.sheet.y=874-(10+21+32+43+data[platform].length*66+(data[platform].length-1)*.5+42);
  const details=sheetBase(group[2].screen,'能力详情',null,true);const gap=fixed(details.sheet,'Header spacing',362,16);const hero=fixed(details.sheet,'音乐连接器概览',362,80);hero.paddingLeft=16;hero.paddingRight=16;hero.itemSpacing=12;hero.fills=paint('white');hero.cornerRadius=20;appIcon(hero,platform,'music',48);const desc=flow(hero,'音乐平台名称','VERTICAL',null,4);desc.layoutSizingHorizontal='FILL';text(desc,platform==='ios'?'Apple Music':'本机音乐',17,'ink','Medium');text(desc,(platform==='ios'?8:7)+' 项能力 · 网关已确认',12,'secondary');text(hero,'已允许',12,'secondary');const heading=fixed(details.sheet,'工具列表标题',362,40);heading.paddingLeft=4;text(heading,'已登记的控制能力',13,'secondary');const tools=flow(details.sheet,'原生能力列表','VERTICAL',362);tools.fills=paint('white');tools.cornerRadius=20;tools.clipsContent=true;
  const toolNames=platform==='ios'?['搜索本地音乐','读取播放状态','播放音乐','暂停音乐','调整播放进度','浏览 Apple Music 曲库','播放曲库歌曲或歌单','搜索 Apple Music 歌曲']:['搜索本机音乐','读取播放状态','播放音乐','暂停音乐','调整播放进度','浏览本机歌曲','打开本机歌曲'];for(const title of toolNames)await nativeToolRow(tools,title);safe(details.sheet);details.sheet.x=0;details.sheet.y=874-(10+21+32+16+80+40+toolNames.length*52+42);
  prototype.push({from:group[0].entry.id,to:group[1].screen.id},{from:group[1].entry.id,to:group[1].screen.id},{from:list.close.id,to:group[0].screen.id},{from:list.scrim.id,to:group[0].screen.id},{from:details.close.id,to:group[0].screen.id},{from:details.backButton.id,to:group[1].screen.id},{from:details.scrim.id,to:group[0].screen.id});
}

const footer=flow(board,'实现说明','VERTICAL',1270,6);text(footer,'统一面板 · 32 点圆角 · 44 点 Logo · 中性灰背景',14,'secondary');text(footer,'点击入口展示面板；点击音乐进入详情。状态和数量以网关实际回包为准。',13,'secondary');
const nodes=board.findAll(()=>true),typeCounts={};for(const n of nodes)typeCounts[n.type]=(typeCounts[n.type]??0)+1;
return {pageId:page.id,boardId:board.id,screens,screenGroups:Object.fromEntries(Object.entries(screenGroups).map(([key,value])=>[key,value.map(g=>g.screen.id)])),createdNodeIds:created,mutatedNodeIds:mutated,prototypeLinks:0,typeCounts,imageNodes:nodes.filter(n=>Array.isArray(n.fills)&&n.fills.some(f=>f.type==='IMAGE')).map(n=>({id:n.id,name:n.name,width:n.width,height:n.height})),fontFamilies:[...new Set(nodes.filter(n=>n.type==='TEXT').map(n=>n.fontName.family))]};
