from PIL import Image, ImageDraw, ImageFont
W,H=1200,630
FONT='/home/claude/dashcams-app/app/src/main/res/font/'
web='/home/claude/josillopillo/dashcams-web/'
OUT='/tmp/promos/'
def bg():
    im=Image.new('RGB',(W,H)); px=im.load()
    TL,TR,BL,BR=(25,27,59),(46,50,122),(18,19,42),(19,20,43)
    for y in range(H):
        v=min(y/580,1)
        for x in range(W):
            u=x/W
            px[x,y]=tuple(int((TL[i]*(1-u)+TR[i]*u)*(1-v)+(BL[i]*(1-u)+BR[i]*u)*v) for i in range(3))
    im.paste(Image.open(web+'og-dashcams.png').convert('RGB').crop((0,580,W,H)),(0,580))
    return im
bf=lambda s: ImageFont.truetype(FONT+'barlow_condensed_bold.ttf',s)
ps=lambda s: ImageFont.truetype(FONT+'public_sans_semibold.ttf',s)
pr=lambda s: ImageFont.truetype(FONT+'public_sans_regular.ttf',s)
escudo=Image.open('/home/claude/dashcams-app/app/src/main/res/drawable-nodpi/escudo.png').convert('RGBA')
def pill(d,text,x=36,y=30):
    f=ps(22); w=d.textlength(text,font=f)
    d.rounded_rectangle((x,y,x+w+64,y+44),22,fill=(37,39,82),outline=(70,74,130),width=2)
    d.ellipse((x+20,y+16,x+32,y+28),fill=(229,57,53)); d.text((x+44,y+9),text,font=f,fill=(235,235,245))
def grad_text(im,xy,text,font):
    mask=Image.new('L',im.size,0); ImageDraw.Draw(mask).text(xy,text,font=font,fill=255)
    bb=mask.getbbox(); g=Image.new('RGB',im.size); gd=ImageDraw.Draw(g)
    for x in range(bb[0],bb[2]+1):
        u=(x-bb[0])/max(1,bb[2]-bb[0]); gd.line((x,0,x,H),fill=tuple(int(a*(1-u)+b*u) for a,b in zip((253,199,40),(245,140,30))))
    im.paste(g,(0,0),mask)
def title(im,lines,size=84,y=104):
    d=ImageDraw.Draw(im); f=bf(size)
    for parts in lines:
        x=36
        for t,hl in parts:
            (grad_text(im,(x,y),t,f) if hl else d.text((x,y),t,font=f,fill='white')); x+=d.textlength(t,font=f)
        y+=size+8
    return y
def sub(d,y,lines):
    for l in lines: d.text((36,y),l,font=pr(26),fill=(205,208,225)); y+=36
def footer(im,parts):
    d=ImageDraw.Draw(im); e=escudo.resize((32,38)); im.paste(e,(36,548),e); f=bf(36); x=78
    for t,hl in parts:
        (grad_text(im,(x,546),t,f) if hl else d.text((x,546),t,font=f,fill='white')); x+=d.textlength(t,font=f)
    return x
# BLOG
im=bg(); d=ImageDraw.Draw(im); pill(d,'Blog de Dashcams España')
y=title(im,[[('Guías claras',False)],[('para conducir ',False)],[('mejor',True)]])
sub(d,y+20,['¿Es legal una dashcam? ¿Cómo se circula','por una glorieta? Lo explicamos fácil.'])
for i,(t,c) in enumerate([('¿Es legal una dashcam?',(253,199,40)),('Cómo circular por una glorieta',(245,140,30)),('Qué dashcam comprar',(120,170,255))]):
    x0,y0=770+i*14,96+i*130
    d.rounded_rectangle((x0,y0,x0+380,y0+150),18,fill='white'); d.rounded_rectangle((x0+18,y0+18,x0+104,y0+132),12,fill=c)
    d.text((x0+122,y0+24),'BLOG',font=ps(16),fill=(120,120,140))
    fz=ps(21); line=''; yy=y0+50
    for w in t.split():
        if d.textlength(line+' '+w,font=fz)>235: d.text((x0+122,yy),line.strip(),font=fz,fill=(20,22,50)); yy+=28; line=''
        line+=' '+w
    d.text((x0+122,yy),line.strip(),font=fz,fill=(20,22,50))
footer(im,[('dashcams',True),('.es/blog',False)]); im.save(OUT+'promo_blog.png')
# YOUTUBE
im=bg(); d=ImageDraw.Draw(im); pill(d,'Canal de YouTube')
y=title(im,[[('Los mejores',False)],[('clips de ',False),('España',True)],[('cada día',False)]])
sub(d,y+20,['Sustos, pillados y lecciones al volante.','Suscríbete a Dashcams España.'])
fr=(740,96,1160,356); d.rounded_rectangle(fr,20,fill=(120,160,220),outline='white',width=6)
d.rectangle((746,200,1154,350),fill=(90,140,80)); d.polygon([(880,350),(1030,350),(962,200),(948,200)],fill=(80,80,95))
d.ellipse((890,166,1010,286),fill=(229,57,53)); d.polygon([(935,196),(935,256),(985,226)],fill='white')
d.rounded_rectangle((770,390,1130,452),31,fill=(229,57,53)); f=ps(26); t='Suscribirme'; d.text((950-d.textlength(t,font=f)/2,402),t,font=f,fill='white')
x=footer(im,[('dashcams',True),('.es',False)]); d.text((x+16,553),'·  @DashcamsEsp',font=ps(24),fill=(205,208,225)); im.save(OUT+'promo_youtube.png')
# TIENDA
im=bg(); d=ImageDraw.Draw(im); pill(d,'Tienda oficial')
y=title(im,[[('Que no se te',False)],[('mueva ',False),('la cámara',True)]])
sub(d,y+20,['Soporte antivibraciones, cable largo','y el merch del canal.'])
s=[(780,150),(880,110),(920,135),(980,135),(1020,110),(1120,150),(1090,230),(1050,215),(1050,470),(850,470),(850,215),(810,230)]
d.polygon(s,fill=(31,33,71)); d.line(s+[s[0]],fill=(90,95,150),width=4)
e=escudo.resize((110,130)); im.paste(e,(895,240),e)
footer(im,[('tienda.',False),('dashcams',True),('.es',False)]); im.save(OUT+'promo_tienda.png')
for n,src in [('promo_recomendador','og-recomendador.png'),('promo_clips','og-envia-tu-clip.png'),('promo_comparador','og-dashcams.png')]:
    Image.open(web+src).convert('RGB').save(OUT+n+'.png')
ims=[Image.open(OUT+f).convert('RGB').resize((600,315)) for f in ['promo_blog.png','promo_youtube.png','promo_tienda.png']]
g=Image.new('RGB',(600,945))
for i,x in enumerate(ims): g.paste(x,(0,i*315))
g.save('/tmp/nuevas.jpg')
