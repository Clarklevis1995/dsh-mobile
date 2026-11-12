# coding: utf-8
"""同视口表格预览的布局/颜色量化；按关闭按钮中心扣除系统顶部安全区差异。"""
import argparse
import json
from pathlib import Path
import re
from collections import Counter
from PIL import Image


def rect(row, native=False):
    return list(map(int, re.findall(r'-?\d+', row.get('origBounds', row['bounds']) if native else row['bounds'])))


def center(box): return [(box[0]+box[2])/2, (box[1]+box[3])/2]


def android_rows(path):
    return [r for w in json.loads(path.read_text()) for r in w.get('content', []) if not r.get('off-screen') and not r.get('hidden')]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--native', type=Path, required=True)
    args = parser.parse_args()
    results = []
    tail = android_rows(args.report/'reference/android-table-preview-dark-scrolled-frozen-layout.json')
    last_column = rect(next(r for r in tail if r.get('text') == '备注'))
    for theme in ['light', 'dark']:
        ref = android_rows(args.report/('reference/android-table-preview-'+theme+'-frozen-layout.json'))
        native = json.loads((args.native/('harmony-table-preview-'+theme+'-layout.json')).read_text())
        reference_image = Image.open(args.report/('reference/android-table-preview-'+theme+'-frozen.png')).convert('RGB')
        native_image = Image.open(args.native/('harmony-table-preview-'+theme+'.png')).convert('RGB')
        assert reference_image.size == native_image.size == (1316, 2832)
        def ref_text(label): return next(r for r in ref if r.get('text') == label)
        def by_id(identifier): return next(r for r in native if r.get('id') == identifier)
        anchor_ref = center(rect(ref_text('关闭')))
        anchor_native = center(rect(by_id('page-back')))
        top_delta = anchor_ref[1] - anchor_native[1]
        metrics = []
        def check(name, expected, actual):
            error = abs(expected-actual)/3.5
            metrics.append(dict(name=name, expectedPx=expected, actualPx=actual, errorVp=error, passed=error<=2))
        for label, identifier in [('表格','document-page-title'), ('关闭','page-back'), ('旋转屏幕','table-reader-rotate'), ('复制表格','table-reader-copy'), ('下载 CSV','table-reader-export')]:
            ref_row = ref_text(label) if label in ['表格','关闭'] else next(r for r in ref if r.get('content-desc') == label)
            a, b = center(rect(ref_row)), center(rect(by_id(identifier)))
            check(label+'中心X', a[0], b[0]); check(label+'相对中心Y', a[1]-anchor_ref[1], b[1]-anchor_native[1])
        viewport_ref = rect(next(r for r in ref if r.get('class') == 'android.widget.HorizontalScrollView'))
        viewport_native = rect(by_id('table-reader-horizontal'))
        for name, index in [('左缘',0), ('右缘',2)]: check('网格'+name, viewport_ref[index], viewport_native[index])
        check('网格相对顶部', viewport_ref[1]-anchor_ref[1], viewport_native[1]-anchor_native[1])
        check('网格高度', viewport_ref[3]-viewport_ref[1], viewport_native[3]-viewport_native[1])
        colors = dict(background='#000000' if theme=='dark' else '#FFFFFF', header='#252527' if theme=='dark' else '#EFEFF0',
                      alternate='#111113' if theme=='dark' else '#F8F8FA', close='#252528' if theme=='dark' else '#F5F5F7')
        ref_rows = sorted([rect(r) for r in ref if r.get('class')=='android.widget.TableRow'], key=lambda b:b[1])
        native_rows = sorted([rect(r, True) for r in native if r.get('type')=='Row' and r.get('backgroundColor') in ['#FF'+value[1:] for value in colors.values()]
                              and rect(r, True)[1] >= viewport_native[1] and rect(r, True)[2]-rect(r, True)[0] >= 1170], key=lambda b:b[1])
        assert len(ref_rows)==len(native_rows)==3
        for index, (a, b) in enumerate(zip(ref_rows, native_rows)):
            check('第'+str(index)+'行顶部', a[1]-anchor_ref[1], b[1]-anchor_native[1])
            check('第'+str(index)+'行高度', a[3]-a[1], b[3]-b[1])
        first = rect(next(r for r in native if r.get('text')=='名称'), True)
        second = rect(next(r for r in native if r.get('text')=='数量'), True)
        first_width = second[0]-first[0]
        check('首列自然宽', rect(ref_text('名称'))[2]-rect(ref_text('名称'))[0], first_width)
        check('末列自然宽', last_column[2]-last_column[0], native_rows[0][2]-native_rows[0][0]-2*first_width)
        glyphs = []
        foreground = (255,255,255) if theme=='dark' else (0,0,0)
        def ink(image, box):
            crop = image.crop(box)
            mask = Image.new('1', crop.size)
            mask.putdata([pixel == foreground for pixel in crop.getdata()])
            value = mask.getbbox(); assert value
            return [box[0]+value[0],box[1]+value[1],box[0]+value[2],box[1]+value[3]]
        for label in ['名称','鲸鱼','海豚','2','3']:
            a = ink(reference_image, rect(ref_text(label)))
            b = ink(native_image, rect(next(r for r in native if r.get('text')==label), True))
            check(label+'字形顶部', a[1]-anchor_ref[1], b[1]-anchor_native[1])
            check(label+'字形底部', a[3]-anchor_ref[1], b[3]-anchor_native[1])
            height_error = abs((b[3]-b[1])-(a[3]-a[1]))/3.5
            glyphs.append(dict(text=label, android=a, harmony=b, heightErrorFp=height_error, passed=height_error<=1))
        sampled = []
        for name, a, b in [('header',ref_rows[0],native_rows[0]), ('background',ref_rows[1],native_rows[1]), ('alternate',ref_rows[2],native_rows[2])]:
            rgb = tuple(int(colors[name][i:i+2],16) for i in [1,3,5])
            actual_ref = reference_image.getpixel((a[0]+60,a[1]+10)); actual_native = native_image.getpixel((b[0]+60,b[1]+10))
            sampled.append(dict(name=name, expected=rgb, android=actual_ref, harmony=actual_native, passed=actual_ref==actual_native==rgb))
        expected_background = tuple(int(colors['background'][i:i+2],16) for i in [1,3,5])
        for name, point_ref, point_native, expected in [
            ('document', (658,1500), (658,1500), expected_background),
            ('close', (int(anchor_ref[0]),int(anchor_ref[1]-60)), (int(anchor_native[0]),int(anchor_native[1]-60)),
             tuple(int(colors['close'][i:i+2],16) for i in [1,3,5]))]:
            a, b = reference_image.getpixel(point_ref), native_image.getpixel(point_native)
            sampled.append(dict(name=name, expected=expected, android=a, harmony=b, passed=a==b==expected))
        icon_color = (170,170,176) if theme=='dark' else (101,101,107)
        for label, identifier in [('旋转屏幕','table-reader-rotate'),('复制表格','table-reader-copy'),('下载 CSV','table-reader-export')]:
            a = rect(next(r for r in ref if r.get('content-desc')==label)); b = rect(by_id(identifier))
            count_ref = Counter(reference_image.crop(a).getdata())[icon_color]
            count_native = Counter(native_image.crop(b).getdata())[icon_color]
            sampled.append(dict(name=label+'图标色', expected=icon_color, androidPixels=count_ref, harmonyPixels=count_native, passed=min(count_ref,count_native)>=10))
        border = (53,53,56) if theme=='dark' else (218,218,221)
        count_ref = Counter(reference_image.crop(viewport_ref).getdata())[border]
        count_native = Counter(native_image.crop(viewport_native).getdata())[border]
        sampled.append(dict(name='border', expected=border, androidPixels=count_ref, harmonyPixels=count_native, passed=min(count_ref,count_native)>=10))
        passed = all(m['passed'] for m in metrics) and all(c['passed'] for c in sampled) and all(g['passed'] for g in glyphs)
        results.append(dict(theme=theme, sourceViewportDp=376, harmonyViewportVp=376, topSystemDifferencePx=top_delta,
                            geometry=metrics, colors=sampled, glyphs=glyphs, fontSizeDeclaredSpFp=17, passed=passed,
                            scope='手机竖屏预览布局/配色；系统状态栏、字形及横屏不同安全区不按整屏像素相似度计分'))
    output = args.report/'geometry-comparison.json'
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(json.dumps(dict(output=str(output), themes=len(results), metrics=sum(len(r['geometry']) for r in results),
                          maximumErrorVp=max(m['errorVp'] for r in results for m in r['geometry']),
                          maximumGlyphSizeErrorFp=max(g['heightErrorFp'] for r in results for g in r['glyphs']),
                          paletteChecks=sum(len(r['colors']) for r in results), passed=all(r['passed'] for r in results))))
    assert all(r['passed'] for r in results)


if __name__ == '__main__': main()
