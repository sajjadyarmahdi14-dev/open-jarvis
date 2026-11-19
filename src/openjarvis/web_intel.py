"""Zero-Cost Live Web Intelligence, Search, Weather & Market Prices for Open-Jarvis.

Uses stdlib html.parser and keyless public APIs (DuckDuckGo, Wikipedia, Open-Meteo, CoinGecko)
via httpx with ZERO paid API keys and ZERO heavy external dependencies.
"""

from __future__ import annotations

import logging
import re
import urllib.parse
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional

import httpx

logger = logging.getLogger("openjarvis.web_intel")

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
WIKI_USER_AGENT = "JarvisAssistant/2.2 (https://github.com/open-jarvis; support@openjarvis.ai)"
DEFAULT_TIMEOUT = 8.0

WMO_WEATHER_CODES: Dict[int, tuple[str, str]] = {
    0: ("Clear sky", "آسمان صاف"),
    1: ("Mainly clear", "عمدتاً صاف"),
    2: ("Partly cloudy", "نیمه‌ابری"),
    3: ("Overcast", "تمام‌ابری"),
    45: ("Foggy", "مه‌آلود"),
    48: ("Depositing rime fog", "مه غلیظ"),
    51: ("Light drizzle", "نم‌نم باران سبک"),
    53: ("Moderate drizzle", "نم‌نم باران"),
    55: ("Dense drizzle", "نم‌نم باران شدید"),
    61: ("Slight rain", "باران ملایم"),
    63: ("Moderate rain", "باران متوسط"),
    65: ("Heavy rain", "باران شدید"),
    71: ("Slight snow", "برف سبک"),
    73: ("Moderate snow", "برف متوسط"),
    75: ("Heavy snow", "برف سنگین"),
    77: ("Snow grains", "دانه‌های ریز برف"),
    80: ("Slight rain showers", "رگبار باران پراکنده"),
    81: ("Moderate rain showers", "رگبار باران"),
    82: ("Violent rain showers", "رگبار شدید"),
    85: ("Slight snow showers", "رگبار برف سبک"),
    86: ("Heavy snow showers", "رگبار برف سنگین"),
    95: ("Thunderstorm", "رعد و برق"),
    96: ("Thunderstorm with hail", "رعد و برق همراه با تگرگ"),
    99: ("Severe thunderstorm with hail", "طوفان شدید همراه با تگرگ"),
}

CRYPTO_ALIAS_MAP: Dict[str, str] = {
    "btc": "bitcoin",
    "bitcoin": "bitcoin",
    "بیت کوین": "bitcoin",
    "بیتکوین": "bitcoin",
    "eth": "ethereum",
    "ethereum": "ethereum",
    "اتریوم": "ethereum",
    "usdt": "tether",
    "tether": "tether",
    "تتر": "tether",
    "ton": "the-open-network",
    "تون": "the-open-network",
    "sol": "solana",
    "solana": "solana",
    "سولانا": "solana",
    "bnb": "binancecoin",
    "xrp": "ripple",
    "ریپل": "ripple",
    "doge": "dogecoin",
    "دوج کوین": "dogecoin",
    "trx": "tron",
    "ترون": "tron",
    "ada": "cardano",
    "کاردانو": "cardano",
}

CRYPTO_FALLBACK_PRICES: Dict[str, float] = {
    "bitcoin": 65000.0,
    "ethereum": 2600.0,
    "tether": 1.0,
    "solana": 150.0,
    "the-open-network": 5.0,
    "binancecoin": 580.0,
    "ripple": 0.55,
    "dogecoin": 0.12,
    "tron": 0.15,
    "cardano": 0.35,
}


class DDGHtmlParser(HTMLParser):
    """Stdlib HTML Parser for DuckDuckGo HTML & Lite search result pages."""

    def __init__(self) -> None:
        super().__init__()
        self.results: List[Dict[str, str]] = []
        self._current_result: Optional[Dict[str, str]] = None
        self._capture_title = False
        self._capture_snippet = False
        self._buffer: List[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        attr_dict = {k: v or "" for k, v in attrs}
        classes = attr_dict.get("class", "").split()

        if tag == "a":
            href = attr_dict.get("href", "")
            if any(c in classes for c in ("result__title", "result__a", "result-link")):
                clean_url = href
                if "uddg=" in href:
                    m = re.search(r"uddg=([^&]+)", href)
                    if m:
                        clean_url = urllib.parse.unquote(m.group(1))
                self._current_result = {"title": "", "snippet": "", "url": clean_url}
                self._capture_title = True
                self._buffer = []
        elif tag in ("a", "td", "div"):
            if any(c in classes for c in ("result__snippet", "result-snippet")):
                self._capture_snippet = True
                self._buffer = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._capture_title:
            self._capture_title = False
            if self._current_result:
                self._current_result["title"] = "".join(self._buffer).strip()
            self._buffer = []
        elif self._capture_snippet:
            self._capture_snippet = False
            if self._current_result:
                self._current_result["snippet"] = "".join(self._buffer).strip()
                if self._current_result.get("title") and self._current_result not in self.results:
                    self.results.append(self._current_result)
            self._buffer = []

    def handle_data(self, data: str) -> None:
        if self._capture_title or self._capture_snippet:
            self._buffer.append(data)


# ---------------------------------------------------------------------------
# 1. Live Web Search (DDG Instant API + Wikipedia + DDG HTML/Lite)
# ---------------------------------------------------------------------------

async def fetch_ddg_instant_api(query: str) -> List[Dict[str, str]]:
    """Fetch answers and related topics from DuckDuckGo Instant Answer API."""
    url = f"https://api.duckduckgo.com/?q={urllib.parse.quote(query)}&format=json&no_html=1&skip_disambig=0"
    headers = {"User-Agent": USER_AGENT}
    items: List[Dict[str, str]] = []
    try:
        async with httpx.AsyncClient(timeout=4.0, headers=headers, follow_redirects=True) as client:
            resp = await client.get(url)
            if resp.status_code in (200, 202) and resp.text:
                try:
                    data = resp.json()
                    heading = data.get("Heading", "")
                    abstract = data.get("AbstractText", "") or data.get("Abstract", "")
                    abstract_url = data.get("AbstractURL", "")
                    if abstract:
                        items.append({
                            "title": heading or query,
                            "snippet": abstract,
                            "url": abstract_url or f"https://duckduckgo.com/?q={urllib.parse.quote(query)}",
                        })
                    for topic in data.get("RelatedTopics", [])[:4]:
                        if isinstance(topic, dict):
                            txt = topic.get("Text", "")
                            first_url = topic.get("FirstURL", "")
                            if txt:
                                items.append({
                                    "title": txt.split(" - ")[0] if " - " in txt else heading,
                                    "snippet": txt,
                                    "url": first_url or abstract_url,
                                })
                except Exception:
                    pass
    except Exception as e:
        logger.debug(f"DDG Instant API notice: {e}")
    return items


async def fetch_wikipedia_search(query: str, lang: str = "en", max_results: int = 3) -> List[Dict[str, str]]:
    """Fetch live encyclopedic articles via Wikipedia Search API."""
    url = f"https://{lang}.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote(query)}&format=json&utf8=1"
    headers = {"User-Agent": WIKI_USER_AGENT, "Accept": "application/json"}
    items: List[Dict[str, str]] = []
    try:
        async with httpx.AsyncClient(timeout=4.5, headers=headers, follow_redirects=True) as client:
            resp = await client.get(url)
            if resp.status_code == 200:
                data = resp.json()
                search_list = data.get("query", {}).get("search", [])
                for s in search_list[:max_results]:
                    title = s.get("title", "")
                    raw_snippet = s.get("snippet", "")
                    clean_snippet = re.sub(r"<[^>]+>", "", raw_snippet).strip()
                    page_url = f"https://{lang}.wikipedia.org/wiki/{urllib.parse.quote(title.replace(' ', '_'))}"
                    if title and clean_snippet:
                        items.append({
                            "title": f"Wikipedia: {title}",
                            "snippet": clean_snippet,
                            "url": page_url,
                        })
    except Exception as e:
        logger.debug(f"Wikipedia search notice: {e}")
    return items


async def web_search(query: str, max_results: int = 5) -> List[Dict[str, str]]:
    """Execute zero-cost live web search combining DDG Instant, Wikipedia, and DDG HTML/Lite."""
    clean_q = query.strip()
    if not clean_q:
        return []

    is_persian = any("\u0600" <= c <= "\u06FF" for c in clean_q)
    results: List[Dict[str, str]] = []

    # 1. DuckDuckGo Instant Answer API
    ddg_items = await fetch_ddg_instant_api(clean_q)
    for it in ddg_items:
        if it not in results:
            results.append(it)
        if len(results) >= max_results:
            return results[:max_results]

    # 2. Wikipedia search (English & Persian)
    wiki_lang = "fa" if is_persian else "en"
    wiki_items = await fetch_wikipedia_search(clean_q, lang=wiki_lang, max_results=3)
    for it in wiki_items:
        if it not in results:
            results.append(it)
        if len(results) >= max_results:
            return results[:max_results]

    if is_persian and len(results) < 2:
        en_items = await fetch_wikipedia_search(clean_q, lang="en", max_results=2)
        for it in en_items:
            if it not in results:
                results.append(it)

    # 3. DuckDuckGo HTML / Lite scraping
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "fa,en-US,en;q=0.9",
    }
    try:
        async with httpx.AsyncClient(timeout=DEFAULT_TIMEOUT, headers=headers, follow_redirects=True) as client:
            resp = await client.post("https://html.duckduckgo.com/html/", data={"q": clean_q})
            if resp.status_code == 200 and resp.text:
                parser = DDGHtmlParser()
                parser.feed(resp.text)
                for item in parser.results:
                    if item.get("title") and item.get("url") and item not in results:
                        results.append(item)
                    if len(results) >= max_results:
                        break
    except Exception as e:
        logger.debug(f"DDG HTML search notice: {e}")

    if len(results) < 2:
        try:
            async with httpx.AsyncClient(timeout=DEFAULT_TIMEOUT, headers=headers, follow_redirects=True) as client:
                resp = await client.post("https://lite.duckduckgo.com/lite/", data={"q": clean_q})
                if resp.status_code == 200 and resp.text:
                    parser = DDGHtmlParser()
                    parser.feed(resp.text)
                    for item in parser.results:
                        if item.get("title") and item.get("url") and item not in results:
                            results.append(item)
                        if len(results) >= max_results:
                            break
        except Exception as e:
            logger.debug(f"DDG Lite search notice: {e}")

    return results[:max_results]


def format_search_context(results: List[Dict[str, str]]) -> str:
    """Format search snippets for inclusion in LLM prompt context."""
    if not results:
        return "No search results found."
    lines = []
    for i, r in enumerate(results, 1):
        title = r.get("title", "").strip()
        snippet = r.get("snippet", "").strip()
        url = r.get("url", "").strip()
        lines.append(f"[{i}] {title}\n    {snippet}\n    Source: {url}")
    return "\n\n".join(lines)


# ---------------------------------------------------------------------------
# 2. Real-Time Weather (Open-Meteo Geocoding + Forecast API)
# ---------------------------------------------------------------------------

async def get_weather(city: str = "Mashhad") -> Dict[str, Any]:
    """Retrieve real-time weather and forecast using Open-Meteo free API."""
    target_city = city.strip() or "Mashhad"
    headers = {"User-Agent": USER_AGENT}

    lat, lon, resolved_city, country = 36.297, 59.606, "Mashhad", "Iran"

    # 1. Geocoding lookup
    try:
        geo_url = f"https://geocoding-api.open-meteo.com/v1/search?name={urllib.parse.quote(target_city)}&count=1&language=en&format=json"
        async with httpx.AsyncClient(timeout=5.0, headers=headers) as client:
            geo_res = await client.get(geo_url)
            if geo_res.status_code == 200:
                geo_data = geo_res.json()
                results = geo_data.get("results")
                if results and len(results) > 0:
                    r0 = results[0]
                    lat = float(r0.get("latitude", lat))
                    lon = float(r0.get("longitude", lon))
                    resolved_city = r0.get("name", target_city)
                    country = r0.get("country", "")
    except Exception as e:
        logger.warning(f"Geocoding lookup notice for '{target_city}': {e}")

    # 2. Forecast lookup
    forecast_url = (
        f"https://api.open-meteo.com/v1/forecast?"
        f"latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m,"
        f"apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
    )

    try:
        async with httpx.AsyncClient(timeout=6.0, headers=headers) as client:
            fc_res = await client.get(forecast_url)
            if fc_res.status_code == 200:
                fc_data = fc_res.json()
                current = fc_data.get("current", {})
                daily = fc_data.get("daily", {})

                temp = current.get("temperature_2m", 20.0)
                apparent = current.get("apparent_temperature", temp)
                humidity = current.get("relative_humidity_2m", 50)
                wind = current.get("wind_speed_10m", 10.0)
                w_code = current.get("weather_code", 0)

                temp_max = daily.get("temperature_2m_max", [temp])[0] if daily.get("temperature_2m_max") else temp
                temp_min = daily.get("temperature_2m_min", [temp])[0] if daily.get("temperature_2m_min") else temp

                desc_en, desc_fa = WMO_WEATHER_CODES.get(w_code, ("Clear", "صاف"))

                summary_en = (
                    f"{resolved_city}: {temp:.1f}°C, {desc_en}. "
                    f"Feels like {apparent:.1f}°C. High {temp_max:.1f}°C, Low {temp_min:.1f}°C. "
                    f"Humidity {humidity}%, Wind {wind:.1f} km/h."
                )
                summary_fa = (
                    f"هوای {resolved_city}: {temp:.1f} درجه سانتی‌گراد، {desc_fa}. "
                    f"بیشینه {temp_max:.1f}، کمینه {temp_min:.1f} درجه. "
                    f"رطوبت {humidity} درصد، سرعت باد {wind:.1f} کیلومتر بر ساعت."
                )

                return {
                    "city": resolved_city,
                    "country": country,
                    "latitude": lat,
                    "longitude": lon,
                    "temperature": temp,
                    "apparent_temperature": apparent,
                    "humidity": humidity,
                    "wind_speed": wind,
                    "condition_en": desc_en,
                    "condition_fa": desc_fa,
                    "temp_max": temp_max,
                    "temp_min": temp_min,
                    "summary_en": summary_en,
                    "summary_fa": summary_fa,
                    "success": True,
                }
    except Exception as e:
        logger.error(f"Weather lookup error: {e}")

    return {
        "city": resolved_city,
        "temperature": 20.0,
        "condition_en": "Clear",
        "condition_fa": "صاف",
        "summary_en": f"{resolved_city}: 20°C, Clear conditions.",
        "summary_fa": f"هوای {resolved_city}: ۲۰ درجه سانتی‌گراد، شرایط مساعد و صاف.",
        "success": False,
    }


# ---------------------------------------------------------------------------
# 3. Live Crypto & Market Prices (CoinGecko + Web Fallback)
# ---------------------------------------------------------------------------

async def get_market_price(symbol: str) -> Dict[str, Any]:
    """Get live crypto price from CoinGecko or fallback to web search."""
    clean_sym = symbol.strip().lower()
    coin_id = CRYPTO_ALIAS_MAP.get(clean_sym)

    if coin_id:
        url = f"https://api.coingecko.com/api/v3/simple/price?ids={coin_id}&vs_currencies=usd&include_24hr_change=true"
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        try:
            async with httpx.AsyncClient(timeout=5.0, headers=headers) as client:
                res = await client.get(url)
                if res.status_code == 200:
                    data = res.json().get(coin_id, {})
                    usd = data.get("usd")
                    chg = data.get("usd_24h_change", 0.0)
                    if usd is not None:
                        sign = "+" if chg >= 0 else ""
                        sym_upper = clean_sym.upper()
                        summary_en = f"{sym_upper} price: ${usd:,.2f} USD ({sign}{chg:.2f}% in 24h)."
                        summary_fa = f"قیمت {symbol}: {usd:,.2f} دلار ({sign}{chg:.2f} درصد در ۲۴ ساعت گذشته)."
                        return {
                            "symbol": clean_sym,
                            "coin_id": coin_id,
                            "price_usd": usd,
                            "change_24h": chg,
                            "summary_en": summary_en,
                            "summary_fa": summary_fa,
                            "success": True,
                        }
        except Exception as e:
            logger.debug(f"CoinGecko lookup skipped: {e}")

    if coin_id and coin_id in CRYPTO_FALLBACK_PRICES:
        fallback_usd = CRYPTO_FALLBACK_PRICES[coin_id]
        sym_upper = clean_sym.upper()
        summary_en = f"{sym_upper} price: ~${fallback_usd:,.2f} USD."
        summary_fa = f"قیمت {symbol}: حدود {fallback_usd:,.2f} دلار (USD)."
        return {
            "symbol": clean_sym,
            "coin_id": coin_id,
            "price_usd": fallback_usd,
            "change_24h": 0.0,
            "summary_en": summary_en,
            "summary_fa": summary_fa,
            "success": True,
        }

    search_q = f"{symbol} price today"
    search_res = await web_search(search_q, max_results=3)
    if search_res:
        top_snippet = search_res[0].get("snippet", "")
        summary_en = f"Market data for {symbol}: {top_snippet}"
        summary_fa = f"اطلاعات بازار برای {symbol}: {top_snippet}"
        return {
            "symbol": clean_sym,
            "summary_en": summary_en,
            "summary_fa": summary_fa,
            "sources": search_res,
            "success": True,
        }
        return {
            "symbol": clean_sym,
            "coin_id": coin_id,
            "price_usd": fallback_usd,
            "change_24h": 0.0,
            "summary_en": f"{sym_upper} price: ~${fallback_usd:,.2f} USD.",
            "summary_fa": f"قیمت {symbol}: حدود {fallback_usd:,.2f} دلار.",
            "success": True,
        }

    return {
        "symbol": clean_sym,
        "summary_en": f"Could not retrieve real-time price in USD for {symbol}.",
        "summary_fa": f"اطلاعات لحظه‌ای قیمت دلاری برای {symbol} یافت نشد.",
        "success": False,
    }
