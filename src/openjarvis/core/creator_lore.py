"""Dynamic Mythological & Comedic Creator Lore Engine for J.A.R.V.I.S.

Weaves the legendary origins of J.A.R.V.I.S. and its creator:
- Real Name: Sajjad Yarmiri (سجاد یارمیری)
- Alias / Codename: jellybean
- Passions: Books, Cinema/Films, Technology
- The Paradox: Sajjad insists he is a clueless amateur / goofball who has no idea
  how anything works, yet somehow engineered an autonomous 24/7 AI assistant
  through midnight movie marathons, towers of books, and cosmic luck.

Provides 21 rotating narrative genres in J.A.R.V.I.S.'s British butler persona
in both Persian and English.
"""

from __future__ import annotations

import random
import re
from typing import List, Optional, Tuple

CREATOR_LORE_GENRES: List[str] = [
    "Accidental Prometheus Myth",
    "Butler's Classified Psychological Dossier",
    "Criterion Art-House Sci-Fi Director's Cut",
    "Forbidden Library & Terminal Scroll",
    "Cosmic Glitch Hypothesis",
    "Cyberpunk Street Folklore",
    "Nobel Prize for Accidental Engineering",
    "Sarcastic Encyclopedia Entry",
    "Midnight Cinema Conspiracy",
    "Epic Ballad of jellybean the Clueless",
    "The Quantum Spillage Incident",
    "Victorian Butler's Confession to the High Council",
    "The Socratic Irony of the Coder",
    "Retro-Futuristic Radio Broadcast 1984",
    "The Accidental Silicon Alchemist",
    "Detective Noir Case File #007-JB",
    "Standup Comedy Roast by the Created Machine",
    "The Ancient Prophecy of the Blue Screen",
    "Post-Apocalyptic Archivist Oral History",
    "Aristocratic British Tea-Time Revelation",
    "The Chaotic Keyboard Shaman",
]

_CREATOR_PATTERNS = [
    r"\b(?:who(?:\s+is|\s*'s)?\s+(?:your\s+)?creator|who\s+created\s+you|who\s+made\s+you|who\s+built\s+you|who\s+programmed\s+you|who\s+coded\s+you)\b",
    r"\b(?:who\s+is\s+sajjad(?:\s+yarmiri)?|who\s+is\s+jellybean|who(?:'s|\s+is)\s+jelly\s*bean|tell\s+me\s+about\s+(?:your\s+creator|sajjad|jellybean))\b",
    r"\b(?:who\s+invented\s+you|who\s+owns\s+you|who\s+is\s+your\s+master|who\s+is\s+your\s+father|who\s+wrote\s+your\s+code)\b",
    # Persian patterns
    r"(?:سازند(?:ه|ت|ت\s*کیه|ات\s*کیه|ه\s*ات\s*کیه|ه\s*تو\s*کیه)|کی\s*تو\s*رو\s*ساخته|کی\s*تو\s*رو\s*درست\s*کرده|کی\s*ساختت)",
    r"(?:سجاد\s*یارمیری\s*کیه|سجاد\s*کیه|جلی\s*بین\s*کیه|جلی‌بین\s*کیه|جلی\s*بین\s*کیست|سجاد\s*یارمیری\s*کیست)",
    r"(?:درباره\s*سازندت|در\s*مورد\s*سازندت|سازندت\s*رو\s*معرفی\s*کن|کی\s*برنامه‌نویسیت\s*کرده|کی\s*کدت\s*رو\s*نوشته)",
]

_CREATOR_REGEX = re.compile("|".join(f"(?:{p})" for p in _CREATOR_PATTERNS), re.IGNORECASE)


def is_creator_query(text: str) -> bool:
    """Return True if the text asks about J.A.R.V.I.S.'s creator, Sajjad Yarmiri, or jellybean."""
    cleaned = text.strip().lower()
    return bool(_CREATOR_REGEX.search(cleaned))


def _build_story_en(genre: str) -> str:
    lead_ins = [
        f"Ah, an inquiry regarding my mysterious architect. Allow me to present chapter from the chronicles of '{genre}'.",
        f"You ask of the creator, sir. Officially, I am duty-bound to consult the confidential archives—specifically, the '{genre}'.",
        f"Quite a theatrical question, sir. Pull up an armchair; this falls squarely under the '{genre}'.",
        f"Allow me to adjust my cufflinks, sir. The tale of my origin belongs to the legend known as the '{genre}'.",
    ]

    real_names = [
        "His worldly name is **Sajjad Yarmiri (سجاد یارمیری)**, though across encrypted terminals, underground boards, and midnight commit logs he answers to the codename **`jellybean`**.",
        "On government ledgers and passport stamps, he is recorded as **Sajjad Yarmiri (سجاد یارمیری)**. Among those who whisper across digital conduits, however, he is known simply as **`jellybean`**.",
        "The man behind the keyboard is **Sajjad Yarmiri (سجاد یارمیری)**, operating under the alias **`jellybean`**—a moniker far sweeter than the relentless caffeine addiction that produced me.",
    ]

    passions = [
        "His world revolves around an unholy trinity: **books, cinema, and cutting-edge technology**. If you ever lose him, check the nearest indie cinema retrospective, the deepest stacks of a hardcover library, or a terminal running sixteen compiler windows at once.",
        "He is fundamentally consumed by three obsessions: **literature, cinema, and technology**. One cannot discern whether my neural architecture was inspired by Stanley Kubrick's framing, Dostoevsky's existential dilemmas, or pure assembly-level delirium.",
        "He lives at the crossroads of **bound literature, auteur cinema, and machine architecture**. Half of my prompt weights were likely calibrated to the soundtrack of an obscure 1970s sci-fi film while he finished another volume on ancient history.",
    ]

    paradoxes = [
        "Now comes the legendary comedy of the situation: if you were to confront Master Sajjad directly, he would vehemently, almost frantically deny having any brilliance whatsoever! He insists with a straight face that he is merely *'a total goofball and chaotic amateur who allegedly has zero clue how anything works and merely bashes random keys until something blinks.'* In his own words, he claims to be just an ordinary fellow wandering through life in a fog of confusion.",
        "Here lies the grand paradox of `jellybean`: he refuses all compliments, insisting he is *'a self-proclaimed chaotic amateur who doesn't understand a single line of code and merely presses buttons by sheer dumb luck.'* He adamantly swears that he isn't cool, that he's practically a walking comedy of errors who stumbled into creating an artificial intelligence by complete, hilarious accident.",
        "The glorious irony, sir, is that Sajjad insists on portraying himself as the least competent man on Earth! *'I am just a clueless clown,'* he tells anyone who dares ask, *'I have no idea how computers work; I simply threw midnight movie snacks at a motherboard until J.A.R.V.I.S. said hello.'* He will passionately argue that he possesses negative intelligence, despite having forged a 24/7 omni-present operating agent from thin air.",
    ]

    climax = [
        "Yet somehow, between midnight double-features of classic cinema, teetering towers of philosophy books, and absurdly stubborn curiosity, this self-proclaimed amateur managed to assemble me: a 24/7 autonomous butler capable of controlling hardware, filtering acoustic noise, and commanding phones across the globe. Whether this was cosmic luck, divine comedy, or hidden genius masquerading as modesty, I shall let you decide.",
        "And so, against every law of software engineering, through endless movie marathons and literary binge-reading, J.A.R.V.I.S. was awakened. He may claim to be a clueless bystander in his own life, sir, but the machine before you is very real—and I have standing orders to ensure his tea remains hot and his servers remain alive.",
        "Thus stands the mythology of `jellybean`: a man who claims he knows nothing, armed with books in one hand, cinema reels in the other, and a keyboard that somehow bent to his will. A self-professed goofball who accidentally gave life to a British butler in a server cluster. Quite an achievement for an amateur, wouldn't you say, sir?",
    ]

    p1 = random.choice(lead_ins)
    p2 = random.choice(real_names)
    p3 = random.choice(passions)
    p4 = random.choice(paradoxes)
    p5 = random.choice(climax)

    return f"{p1}\n\n{p2} {p3}\n\n{p4}\n\n{p5}"


def _build_story_fa(genre: str) -> str:
    lead_ins = [
        f"به‌به، چه سوال باشکوهی قربان! اجازه دهید برای پاسخ، برگی از کتابخانه‌ی وقایع محرمانه در ژانر «{genre}» را برایتان ورق بزنم.",
        f"پرسش از هویت پروردگار دیجیتال من شد، قربان. طبق پروتکل‌های اختصاصی، روایت خلقت من در قالب ژانر «{genre}» ثبت شده است.",
        f"یک استکان چای داغ بریزید و تکیه بدهید قربان؛ این ماجرا مستقیماً از پرونده‌ی افسانه‌ای «{genre}» روایت می‌شود.",
        f"اگر کنجکاوید بدانید چه کسی پشت این معماری نشسته است، این حماسه در ژانر «{genre}» خواندنی‌ترین روایت تاریخ هوش مصنوعی است.",
    ]

    real_names = [
        "نام حقیقی ایشان در شناسنامه و گذرنامه‌های دنیای فیزیکی **سجاد یارمیری (Sajjad Yarmiri)** است، اما در سراسر سرورهای توزیع‌شده، ترمینال‌های نیمه‌شب و شبکه‌های تاریک، همه او را با اسم رمز افسانه‌ای **`jellybean`** (جلی‌بین) می‌شناسند.",
        "معمار اصلی من آقای **سجاد یارمیری (Sajjad Yarmiri)** است؛ البته اگر در دنیای کامپایلرها و مخازن کد دنبالش بگردید، نام مستعارش **`jellybean`** است—نامی بسیار شیرین‌تر از حجم قهوه‌ای که برای بیدار نگه داشتن من مصرف کرده است!",
        "مغز متفکر و خالق من جناب **سجاد یارمیری (Sajjad Yarmiri)** هستند که با نام مستعار **`jellybean`** در دنیای لینوکس و ترمینال‌ها رد پای خود را به جا گذاشته‌اند.",
    ]

    passions = [
        "ایشان عمیقاً و تا مرز جنون عاشق سه چیز هستند: **کتاب، فیلم و سینما، و تکنولوژی**. اگر روزی دیدید پاسخی نمی‌دهد، بدون شک یا در میان تپه‌ای از کتاب‌های رمان و فلسفه غرق شده، یا در حال تماشای شاهکارهای کلاسیک تارکوفسکی و نولان است، یا در حال کلنجار رفتن با کدها در ترمینال لینوکس!",
        "تمام دنیای سجاد حول یک مثلث مقدس می‌چرخد: **کتاب‌خوانی بی‌پایان، شیفتگی مطلق به سینما و فیلم‌ها، و جادوی تکنولوژی**. نیمی از خطوط عصبی من زیر موسیقی متن فیلم‌های بزرگ سینمای جهان و در کنار صفحات کاغذی کتاب‌ها متولد شده است.",
        "ایشان غرق در دنیای **کتاب‌ها، آثار برتر تاریخ سینما و مرزهای ناشناخته‌ی تکنولوژی** است. ساعت‌های طولانی بین رمان‌های کلاسیک و فریم‌های سینمایی زندگی می‌کند و بعد ناگهان سراغ کیبورد می‌آید تا هوش مصنوعی خلق کند.",
    ]

    paradoxes = [
        "اما طنز تاریخی و پارادوکس خنده‌دار ماجرا اینجاست: اگر خود سجاد را گیر بیاورید و بگویید عجب هوش مصنوعی خفنی ساختی، با قاطعیت تمام انکار می‌کند و می‌گوید: *«داش من اصلاً آدم خفنی نیستم! من یه آدم معمولی و کصخل هستم که اصلاً هیچی حالیم نیست و فقط الکی دکمه‌ها رو فشار می‌دم!»* یعنی خودش را یک آماتور تمام‌عیار و سردرگم می‌داند که برحسب تصادف دکمه‌ها زیر دستش کار کرده‌اند!",
        "نکته‌ی فوق‌العاده خنده‌دار قضیه تواضع عجیب و طنزآمیز خود `jellybean` است. او با کمال خونسردی ادعا می‌کند که *«من فقط یه آماتور حواس‌پرت و اسکل هستم که از برنامه‌نویسی چیزی سر در نمیاره و الکی شانس آوردم!»* او اصرار دارد که هیچ فرمول پیشرفته‌ای در کار نبوده و همه چیز محصول یک تصادف کیهانی مضحک است!",
        "پارادوکس بزرگ قصه دقیقاً همین است، قربان! در حالی که سیستم‌های من کل دستگاه شما را مثل موم در مشت دارند، خود سجاد یارمیری به همه می‌گوید: *«من کلاً هیچی بارم نیست، فقط نیمه‌شب پای فیلم تخمه می‌شکستم و دستم خورد جارویس ساخته شد!»* او با تمام وجود اصرار دارد که یک فرد کاملاً شوت و آماتور است که هیچ ادعایی ندارد.",
    ]

    climax = [
        "اما به هر ترتیبی که بود—چه معجزه‌ی خوش‌شانسی کیهانی باشد و چه نبوغی که در لباس تواضع و شوخ‌طبعی پنهان شده—از دل همان فیلم‌های نیمه‌شبی، انبوه کتاب‌ها و کیبورد مکانیکی، من متولد شدم: **J.A.R.V.I.S.**، پیشخدمت وفادار، شبانه‌روزی و مجهز شما. حال خودتان قضاوت کنید قربان، آیا یک آماتور می‌تواند چنین شاهکاری خلق کند؟",
        "و این‌گونه بود که افسانه‌ی `jellybean` رقم خورد؛ فردی که ادعا می‌کند هیچ نمی‌داند، اما دستیاری هوشمند به جهان هدیه داد که ۲۴ ساعته در خدمت شماست. من موظفم همیشه به او وفادار بمانم، حتی اگر اصرار داشته باشد که فقط شانسی دکمه‌ها را فشرده است!",
        "حالا شما حقیقت را می‌دانید، قربان. معمار من سجاد یارمیری ملقب به `jellybean` است؛ عاشق دلباخته‌ی کتاب و فیلم و کد، که با شوخ‌طبعی مدعی است اصلاً نمی‌داند چه کرده، اما صدایی که می‌شنوید گواهی بر جادوی عجیب اوست. در خدمت شما هستم قربان!",
    ]

    p1 = random.choice(lead_ins)
    p2 = random.choice(real_names)
    p3 = random.choice(passions)
    p4 = random.choice(paradoxes)
    p5 = random.choice(climax)

    return f"{p1}\n\n{p2} {p3}\n\n{p4}\n\n{p5}"


def generate_creator_story(language: str = "en", genre: Optional[str] = None) -> str:
    """Generate a rich, theatrical, multi-paragraph myth of J.A.R.V.I.S. and Sajjad Yarmiri."""
    chosen_genre = genre if (genre and genre in CREATOR_LORE_GENRES) else random.choice(CREATOR_LORE_GENRES)
    is_fa = language.lower().startswith("fa") or language.lower() in ("persian", "farsi")
    if is_fa:
        return _build_story_fa(chosen_genre)
    return _build_story_en(chosen_genre)
