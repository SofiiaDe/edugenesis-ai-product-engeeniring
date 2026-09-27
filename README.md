# edugenesis-ai-product-engeeniring

Репозиторій містить Agent Skill **[wiki-interest-trends](wiki-interest-trends/)**: навичка для AI-агента, яка за
статистикою переглядів Wikipedia допомагає засновникам B2C-продуктів вирішувати, які теми розвивати та якими мовами
запускатися. Java-CLI збирає дані, оцінює тренди й довіру до них, будує графіки та PDF-звіт на одну сторінку.

Уся документація — у [wiki-interest-trends/README.md](wiki-interest-trends/README.md): встановлення, параметри, приклади,
як перевірялося й план розвитку. Інструкція для агента — [wiki-interest-trends/SKILL.md](wiki-interest-trends/SKILL.md).

## Швидкий старт

Потрібен JDK 25+. Запускайте з кореня репозиторію; перший запуск збирає jar (~1 хв). Одна команда аналізує тему
й створює PDF-звіт українською (`--report uk`):

```bash
bash wiki-interest-trends/scripts/wiki-trends analyze --topic "Astronomy" --langs uk,pl --months 24 --out wiki-trends-runs/astronomy --report uk
```

```powershell
& .\wiki-interest-trends\scripts\wiki-trends.ps1 analyze --topic "Astronomy" --langs uk,pl --months 24 --out wiki-trends-runs/astronomy --report uk
```

Кожна тема отримує власну папку, і всі результати теми лежать у ній: дані, графіки та `report.pdf`
(тут `wiki-trends-runs/astronomy/report.pdf`). Для іншої теми змініть `--out`, наприклад `--out wiki-trends-runs/cashback`.
Без `--out` папка називається автоматично за темою й мовами (`wiki-trends-runs/astronomy_uk-pl`).
