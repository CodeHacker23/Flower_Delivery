#!/usr/bin/env python3
"""
Геокодирует адреса Челябинска через Nominatim (OSM).
Печатает только если результат в городе Челябинск (не Копейск, Касли, Троицк и т.д.).
"""
import urllib.request
import urllib.parse
import json
import time

ADDRESSES = [
    "Цвиллинга 45", "Энгельса 43", "Молодогвардейцев 68", "Калинина 20", "Труда 72",
    "Кирова 84", "Свободы 97", "Воровского 28", "Красная 38", "Проспект Ленина 83",
    "Тимирязева 42", "Новороссийская 53", "Тухачевского 10а", "Бажова 55", "Пирогова 1",
    "Комсомольский пр. 30", "Свердловский пр. 59", "Братьев Кашириных 131", "Васенко 92", "Гагарина 6",
    "Дзержинского 94", "Елькина 86", "Зальцмана 2", "Игуменка 18", "Кожевенная 54",
    "Лесопарковая 4", "Молодогвардейцев 31", "Островского 15", "Победы 160", "Российская 61",
    "Сала Южного 34", "Танкистов 126", "Университетская Набережная 34", "Худякова 12", "Цвиллинга 25",
    "Энгельса 44", "40-летия Победы 15", "Блюхера 53", "Ворошилова 15", "Грибоедова 2",
    "Доватора 26", "Жукова 34", "Курчатова 17", "Молодогвардейцев 7", "Сони Кривой 73",
    "Труда 88", "Кирова 114", "Свободы 155", "Калинина 27", "Братьев Кашириных 98",
]

SKIP_CITIES = ("Kopeysk", "Kasli", "Troitsk", "Chebarkul", "Fershampenuaz", "Amur", "Zolotaya Sopka", "CHKPZ township")

def geocode(addr):
    q = f"Челябинск {addr}"
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"q": q, "format": "json", "limit": 5})
    req = urllib.request.Request(url, headers={"User-Agent": "FlowerDelivery-Test/1.0"})
    with urllib.request.urlopen(req, timeout=10) as r:
        data = json.loads(r.read().decode())
    for item in data:
        dn = item.get("display_name", "")
        if "Chelyabinsk" not in dn:
            continue
        if any(skip in dn for skip in SKIP_CITIES):
            continue
        lat = float(item["lat"])
        lon = float(item["lon"])
        return lat, lon
    return None

def main():
    print("-- Все координаты геокодированы (Nominatim), только Челябинск город")
    print("WITH addresses(n, addr, lat, lon) AS (")
    print("  VALUES")
    for i, addr in enumerate(ADDRESSES, 1):
        time.sleep(1.1)
        coords = geocode(addr)
        if coords:
            lat, lon = coords
            print(f"    ({i},  '{addr}', {lat:.6f}, {lon:.6f}),")
        else:
            print(f"    -- ({i} '{addr}' - не найден в Челябинске, подставь вручную),")
    print(")")

if __name__ == "__main__":
    main()
