import sys
sys.argv=['x']
exec(open('/tmp/promos/gen.py').read().split('# BLOG')[0])
from PIL import Image, ImageDraw, ImageFilter
R='/tmp/claude-tool-results/0c6d6436-afce-52e9-964b-c31a25814b0d/'
blog=Image.open(R+'mcp-claude-in-chrome-blob-1795645383758-aktyeo-98d82485.jpg').convert('RGB')
yt=Image.open(R+'mcp-claude-in-chrome-blob-1795645352740-xvc02f-03472e39.jpg').convert('RGB')

def window(im, shot, box, url):
    x0,y0,x1,y1=box; w=x1-x0; bar=34
    fit=bar+round(shot.height*w/shot.width)
    if fit<y1-y0:
        y0=y0+((y1-y0)-fit)//2; y1=y0+fit; box=(x0,y0,x1,y1)
    h=y1-y0
    # sombra
    sh=Image.new('L',im.size,0); ImageDraw.Draw(sh).rounded_rectangle((x0+10,y0+16,x1+10,y1+16),18,fill=150)
    im.paste((8,9,22),(0,0),sh.filter(ImageFilter.GaussianBlur(14)))
    d=ImageDraw.Draw(im)
    d.rounded_rectangle(box,16,fill=(235,236,242))
    for i,c in enumerate([(237,95,87),(245,190,79),(98,197,84)]):
        d.ellipse((x0+16+i*20,y0+12,x0+28+i*20,y0+24),fill=c)
    d.rounded_rectangle((x0+86,y0+8,x1-16,y0+27),9,fill='white')
    d.text((x0+98,y0+9),url,font=pr(14),fill=(90,92,110))
    s=shot.copy(); s.thumbnail((w,10**6))
    s=s.crop((0,0,w,min(s.height,h-bar)))
    m=Image.new('L',(w,h-bar),0); ImageDraw.Draw(m).rounded_rectangle((0,-20,w,h-bar),16,fill=255)
    canvas=Image.new('RGB',(w,h-bar),(20,20,30)); canvas.paste(s,(0,0))
    im.paste(canvas,(x0,y0+bar),m)

# BLOG
im=bg(); d=ImageDraw.Draw(im); pill(d,'Blog de Dashcams España')
y=title(im,[[('Guías claras',False)],[('para conducir',False)],[('mejor',True)]])
sub(d,y+20,['¿Es legal una dashcam? ¿Cómo','instalarla? ¿Cuál comprar?'])
shot=blog.crop((446,0,1112,560))
window(im,shot,(580,60,1170,520),'dashcams.es/blog')
footer(im,[('dashcams',True),('.es/blog',False)]); im.save(OUT+'promo_blog.png')

# YOUTUBE: cabecera del canal + fila de vídeos (sin los botones de gestión ni los avatares de miembros)
top=yt.crop((452,30,1254,252)); row=yt.crop((452,592,1254,748))
shot=Image.new('RGB',(802,top.height+row.height+14),(15,15,15)); shot.paste(top,(0,0)); shot.paste(row,(0,top.height+14))
im=bg(); d=ImageDraw.Draw(im); pill(d,'Canal de YouTube')
y=title(im,[[('Los mejores',False)],[('clips de ',False),('España',True)],[('cada día',False)]])
sub(d,y+20,['132 mil suscriptores y más de 4.000','vídeos. Suscríbete a Dashcams España.'])
window(im,shot,(560,70,1170,490),'youtube.com/@DashcamsEsp')
x=footer(im,[('dashcams',True),('.es',False)]); d=ImageDraw.Draw(im); d.text((x+16,553),'·  @DashcamsEsp',font=ps(24),fill=(205,208,225))
im.save(OUT+'promo_youtube.png')
ims=[Image.open(OUT+f).convert('RGB') for f in ['promo_blog.png','promo_youtube.png']]
g=Image.new('RGB',(1200,1260))
for i,x in enumerate(ims): g.paste(x,(0,i*630))
g.resize((800,840)).save('/tmp/nuevas2.jpg')
