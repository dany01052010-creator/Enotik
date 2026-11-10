#!/bin/bash
# Делаем игру офлайн: кладём three.js внутрь приложения и меняем ссылку в index.html
set -e
cd app/src/main/assets
curl -fsSL https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js -o three.min.js
test "$(wc -c < three.min.js)" -gt 100000
sed -i 's#https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js#three.min.js#' index.html
grep -c 'src="three.min.js"' index.html
