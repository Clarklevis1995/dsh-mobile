const ids = [];
const track = n => { ids.push(n.id); return n; };
const rgb = hex => figma.util.rgb(hex);
const solid = hex => [{type:'SOLID',color:rgb(hex)}];
await Promise.all([{family:'SF Pro',style:'Regular'},{family:'SF Pro',style:'Semibold'},
  {family:'Noto Sans SC',style:'Regular'},{family:'Noto Sans SC',style:'Medium'}].map(f=>figma.loadFontAsync(f)));
const white = await figma.variables.importVariableByKeyAsync('0c1a82745bdff17e14d0d78142c756a4d97a74eb');
const grouped = await figma.variables.importVariableByKeyAsync('a90ed703166e4696edc327f717296a29491d2124');
const bodyStyle = await figma.importStyleByKeyAsync('5e51bb075a2e98b7f8345cbb49bcf155a767717f');
await figma.loadFontAsync(bodyStyle.fontName);
const col = figma.variables.createVariableCollection('Whaleness / Map picker');
col.renameMode(col.modes[0].modeId,'Light');
const tokens={};
for(const [name,value] of Object.entries({ink:'#171A20',secondary:'#747880',separator:'#E8E8ED',accent:'#2E6BE6',handle:'#C3C4CA'})) {
  const v=figma.variables.createVariable(name,col,'COLOR');v.scopes=['TEXT_FILL','FRAME_FILL','SHAPE_FILL','STROKE_COLOR'];
  v.setValueForMode(col.modes[0].modeId,{...rgb(value),a:1});tokens[name]=v;
}
const paint = v => [figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:1,g:1,b:1}},'color',v)];
function layout(parent,name,direction='VERTICAL',width=null,gap=0) {
  const n=track(figma.createAutoLayout(direction));n.name=name;n.fills=[];n.itemSpacing=gap;
  parent.appendChild(n);if(width){n.resize(width,10);n.primaryAxisSizingMode=direction==='VERTICAL'?'AUTO':'FIXED';n.counterAxisSizingMode=direction==='VERTICAL'?'FIXED':'AUTO';}return n;
}
function text(parent,str,size=17,weight='Regular',color='ink',width=null) {
  const n=track(figma.createText());n.name=str;n.fontName={family:/[^\x00-\x7F]/.test(str)?'Noto Sans SC':'SF Pro',style:weight==='Semibold'?(/[^\x00-\x7F]/.test(str)?'Medium':'Semibold'):'Regular'};
  n.fontSize=size;n.lineHeight={unit:'PIXELS',value:Math.round(size*1.45)};n.characters=str;n.fills=paint(tokens[color]);parent.appendChild(n);
  if(width){n.textAutoResize='HEIGHT';n.resize(width,Math.round(size*1.45));n.layoutSizingHorizontal='FIXED';}else n.textAutoResize='WIDTH_AND_HEIGHT';
  return n;
}
function rect(parent,name,w,h,fill,r=0) {
  const n=track(figma.createRectangle());n.name=name;n.resize(w,h);n.fills=fill;n.cornerRadius=r;parent.appendChild(n);return n;
}
function svg(parent,code,w,h,name) {const n=track(figma.createNodeFromSvg(code));n.name=name;n.resize(w,h);parent.appendChild(n);return n;}
function chevron(parent){return svg(parent,'<svg width="24" height="24" viewBox="0 0 24 24"><path d="M9 5L16 12L9 19" fill="none" stroke="#A1A3AA" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',18,18,'Chevron / source Android ic_chevron_right');}
const iconIds={apple:'3:25',amap:'3:26',baidu:'3:27',google:'3:28'};
const iconComponents={};for(const key of Object.keys(iconIds))iconComponents[key]=await figma.getNodeByIdAsync(iconIds[key]);
const row=track(figma.createComponent());row.name='Map picker / App row';row.description='整行点击打开从当前位置出发的路线。App name 和 App icon 可替换；未安装的应用不展示。';
row.layoutMode='HORIZONTAL';row.resize(354,72);row.primaryAxisSizingMode='FIXED';row.counterAxisSizingMode='FIXED';row.counterAxisAlignItems='CENTER';row.paddingLeft=16;row.paddingRight=14;row.itemSpacing=14;row.fills=paint(white);row.x=650;row.y=1300;
const labelProp=row.addComponentProperty('App name','TEXT','地图应用');
const iconProp=row.addComponentProperty('App icon','INSTANCE_SWAP',iconComponents.apple.id);
const icon=track(iconComponents.apple.createInstance());row.appendChild(icon);icon.resize(44,44);icon.componentPropertyReferences={mainComponent:iconProp};
const label=text(row,'地图应用',17);label.textStyleId=bodyStyle.id;label.componentPropertyReferences={characters:labelProp};label.layoutSizingHorizontal='FILL';chevron(row);
const board=layout(figma.currentPage,'Whaleness / 地图应用选择 / Design review','VERTICAL',1000,28);board.x=100;board.y=100;board.paddingLeft=48;board.paddingRight=48;board.paddingTop=40;board.paddingBottom=40;board.fills=solid('#EDF0F4');board.cornerRadius=28;
const intro=layout(board,'Design title','VERTICAL',null,6);
text(intro,'WHALENESS  /  MAP PICKER',12,'Semibold','secondary');text(intro,'选择地图应用',30,'Semibold');text(intro,'官方应用图标 · 点击后查看路线',14,'Regular','secondary');
const compare=layout(board,'iOS and Android','HORIZONTAL',null,40);compare.counterAxisAlignItems='MIN';
const screens=[];
function appRow(parent,key,name,width){const i=track(row.createInstance());parent.appendChild(i);i.resize(width,72);i.setProperties({[labelProp]:name,[iconProp]:iconComponents[key].id});return i;}
function phone(platform,width,height,providers) {
  const column=layout(compare,platform+' preview','VERTICAL',width,12);
  const cap=layout(column,'Platform label','HORIZONTAL',width,0);text(cap,platform,14,'Semibold');
  const screen=track(figma.createFrame());screen.name=platform+' / 地图应用选择';screen.resize(width,height);screen.cornerRadius=44;screen.clipsContent=true;screen.fills=paint(white);column.appendChild(screen);screens.push(screen.id);
  const context=layout(screen,'Conversation context','VERTICAL',width,24);context.paddingLeft=24;context.paddingRight=24;context.paddingTop=18;
  const status=layout(context,'Status bar','HORIZONTAL',width-48,0);status.primaryAxisAlignItems='SPACE_BETWEEN';text(status,'9:41',15,'Semibold');text(status,'5G   ▰',13,'Semibold');
  if(platform==='iOS') {const island=rect(screen,'Dynamic Island',110,28,solid('#080808'),14);island.x=(width-110)/2;island.y=12;}
  const nav=layout(context,'Conversation navigation','HORIZONTAL',width-48,12);nav.counterAxisAlignItems='CENTER';text(nav,'‹',30,'Regular');text(nav,'周末出行',18,'Semibold');
  const query=layout(context,'User message','VERTICAL',width-82,0);query.paddingLeft=14;query.paddingRight=14;query.paddingTop=10;query.paddingBottom=10;query.cornerRadius=20;query.fills=solid('#EDF3FF');text(query,'帮我导航去上海博物馆',16,'Regular','ink',width-110);
  text(context,'可以，选择你常用的地图查看路线。',16,'Regular','ink',width-48);
  const card=layout(context,'Map card','VERTICAL',width-48,5);card.paddingLeft=16;card.paddingTop=14;card.paddingBottom=14;card.cornerRadius=18;card.fills=paint(grouped);text(card,'上海博物馆',17,'Semibold');text(card,'选择地图应用  ›',13,'Regular','secondary');
  const scrim=rect(screen,'Modal scrim',width,height,[{type:'SOLID',color:{r:0,g:0,b:0},opacity:0.32}]);
  const sheet=layout(screen,'Bottom Sheet','VERTICAL',width,0);sheet.fills=paint(grouped);sheet.topLeftRadius=32;sheet.topRightRadius=32;sheet.bottomLeftRadius=0;sheet.bottomRightRadius=0;
  sheet.paddingTop=10;sheet.paddingLeft=20;sheet.paddingRight=20;
  const grab=layout(sheet,'Grabber','HORIZONTAL',width-40,0);grab.primaryAxisAlignItems='CENTER';grab.paddingBottom=16;rect(grab,'Drag indicator',36,5,paint(tokens.handle),3);
  const header=layout(sheet,'Header','HORIZONTAL',width-40,0);header.primaryAxisAlignItems='SPACE_BETWEEN';header.counterAxisAlignItems='CENTER';text(header,'选择地图应用',21,'Semibold');
  const close=layout(header,'Close button','HORIZONTAL',32,0);close.resize(32,32);close.primaryAxisSizingMode='FIXED';close.counterAxisSizingMode='FIXED';close.primaryAxisAlignItems='CENTER';close.counterAxisAlignItems='CENTER';close.fills=solid('#E6E6EB');close.cornerRadius=16;text(close,'×',23,'Regular','secondary');
  const dest=layout(sheet,'Destination','VERTICAL',width-40,3);dest.paddingTop=14;dest.paddingBottom=18;text(dest,'上海博物馆',17,'Semibold');text(dest,'从当前位置出发 · 驾车',13,'Regular','secondary');
  const list=layout(sheet,'Installed map apps','VERTICAL',width-40,0);list.cornerRadius=20;list.clipsContent=true;list.fills=paint(white);
  providers.forEach(([key,name],index)=>{appRow(list,key,name,width-40);if(index<providers.length-1){const line=layout(list,'Inset separator','HORIZONTAL',width-40,0);line.paddingLeft=74;rect(line,'Separator',width-114,0.5,paint(tokens.separator));}});
  const web=layout(sheet,'Web map fallback','HORIZONTAL',width-40,10);web.paddingTop=17;web.paddingBottom=17;web.primaryAxisAlignItems='CENTER';web.counterAxisAlignItems='CENTER';
  const globe=text(web,figma.util.getSfSymbolCharacter('globe'),17,'Regular','accent');globe.fontName={family:'SF Pro',style:'Regular'};text(web,'使用网页地图',14,'Regular','accent');
  const safe=layout(sheet,'System safe area','HORIZONTAL',width-40,0);safe.primaryAxisAlignItems='CENTER';safe.paddingTop=9;safe.paddingBottom=9;rect(safe,'Home indicator',platform==='iOS'?134:108,5,solid('#16171B'),3);
  sheet.y=height-sheet.height;sheet.x=0;
  text(column,platform==='iOS'?'苹果 / 高德 / 百度 / 谷歌':'高德 / 百度 / 谷歌',12,'Regular','secondary');
  return {id:screen.id,sheetId:sheet.id,sheetHeight:sheet.height,sheetY:sheet.y};
}
const ios=phone('iOS',402,874,[['apple','苹果地图'],['amap','高德地图'],['baidu','百度地图'],['google','谷歌地图']]);
const android=phone('Android',402,874,[['amap','高德地图'],['baidu','百度地图'],['google','谷歌地图']]);
const notes=layout(board,'Handoff notes','VERTICAL',null,6);text(notes,'仅显示已安装应用；从其他 App 返回后刷新列表。',13,'Regular','secondary');text(notes,'点整行进入路线页，保持已有的步行 / 公交方式；普通地点默认驾车。',13,'Regular','secondary');
for(const c of Object.values(iconComponents)){if(c.fills.some(p=>p.type==='IMAGE')===false)throw new Error('Missing official icon fill: '+c.name);}
return {createdNodeIds:ids,mutatedNodeIds:[],boardId:board.id,screens:[ios,android],rowComponentId:row.id,rowPropertyKeys:{label:labelProp,icon:iconProp},imageComponents:iconIds,editableTextCount:board.findAllWithCriteria({types:['TEXT']}).length,instanceCount:board.findAllWithCriteria({types:['INSTANCE']}).length};
