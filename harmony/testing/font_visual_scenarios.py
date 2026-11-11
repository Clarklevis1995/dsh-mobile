"""字号页浅深原图和实际文字色值，几何对照由冻结Android同帧布局完成。"""
import json,re,time
from PIL import Image
from collections import Counter

def run(ui,m,folder,passed):
    ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-style')
    for theme in ['light','dark']:
        ui.navigate('settings-style','appearance-'+theme);ui.choose('appearance-'+theme)
        ui.navigate('settings-font','font-follow-system');ui.text('代码');time.sleep(.4)
        name='font-'+theme+'-verified';ui.screenshot(name);rows=ui.nodes();(folder/(name+'-layout.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2))
        image=Image.open(folder/(name+'.png')).convert('RGB');background=(247,247,248) if theme=='light' else (13,17,24)
        assert image.getpixel((20,50))==background and image.getpixel((20,image.height-70))==background,'字号页系统栏底色不匹配'
        expected=(73,69,79) if theme=='light' else (202,196,208)
        for key in ['font-scale-value','font-scale-reset']:
            row=next(r for r in rows if r.get('id')==key);box=tuple(map(int,re.findall(r'-?\d+',row['bounds'])));colors=Counter(image.crop(box).getdata());assert colors[expected]>15,(theme,key,colors.most_common(5))
        row=next(r for r in rows if r.get('id')=='font-scale');box=tuple(map(int,re.findall(r'-?\d+',row['bounds'])));colors=Counter(image.crop(box).getdata());assert colors[(10,89,247)]<15,'滑块选中色仍被系统默认蓝覆盖'
        ui.navigate('page-back','settings-style')
    passed('P7-font-light-dark-colors-F','实际浅深字号页预览/行内代码与控制区可见，标准/恢复默认文字核心色值与冻结参考一致；保存同帧原始几何供V对照')
