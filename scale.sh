convert icon.png -resize 360x360 icon360.png
convert icon.png -resize 512x512 icon512.png
convert icon.png -resize 900x900 banner1600.png
cp banner1600.png banner3000.png
mogrify -gravity center -background black -extent 1600x banner1600.png
mogrify -gravity center -background black -extent 3000x banner3000.png
convert icon.png -resize 1440x1440 banner2560.png
mogrify -gravity center -background black -extent 2560x banner2560.png
mogrify -gravity center -background black -extent 2560x banner2560.png
convert icon.png -resize 1008x1008 icon1008.png
mogrify -gravity center -background black -extent 1008x1440 icon1008.png
