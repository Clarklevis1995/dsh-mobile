"""生成纯色测试图和明确 EXIF 方位；不使用用户照片。"""
from pathlib import Path
from PIL import Image, ImageDraw
import json


def generate(raw):
    folder = raw / 'orientation-fixtures'; folder.mkdir(parents=True,exist_ok=True)
    base = Image.new('RGB',(96,64)); draw = ImageDraw.Draw(base)
    for box,color in [((0,0,47,31),(240,20,20)),((48,0,95,31),(20,220,20)),((0,32,47,63),(20,20,240)),((48,32,95,63),(230,220,20))]: draw.rectangle(box,fill=color)
    for value in range(1,9):
        exif = Image.Exif(); exif[274] = value; base.save(folder/('exif-'+str(value)+'.jpg'),quality=96,exif=exif)
    exif = Image.Exif(); exif[274] = 6; base.resize((4096,2730)).save(folder/'large-rotated.jpg',quality=96,exif=exif)
