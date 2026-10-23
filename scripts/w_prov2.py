import io

path = 'docs/OPEN-SOURCE-PROVENANCE.md'
s = io.open(path, encoding='utf-8').read()

old = """**現況**：本專案與 DHC 透過**加密匯出格式**互動，
**沒有共用原始碼**。這是刻意設計的，也讓兩邊可以各自授權。"""

new = """**現況**：本專案與 DHC 透過**加密匯出格式**互動，
**沒有共用原始碼**。這是刻意設計的，也讓兩邊可以各自授權。

**已實際檢查**（本輪）：

```bash
$ grep -ril "GPL\\|AGPL" --include=*.kt --include=*.java --include=*.py app/ integrations/
integrations/dhc/importer/importer.py

$ find app integrations -type d \\( -name third_party -o -name vendor -o -name external \\)
（無輸出）
```

唯一的命中是 `integrations/dhc/importer/importer.py` 的一行**註解**，
它說明這個檔案是**獨立實作**、沒有複製 AGPL 原始碼。
那不是 AGPL 原始碼，是**關於授權的說明**。

而且 `protocol/contract.py` 與 `protocol/crypto.py` **不在這個 repository 裡** ——
它們屬於 DHC repository。所以這個 adapter 是依照**契約**寫的，
不是把實作搬過來。"""
assert old in s, 'the DHC section was not found'
s = s.replace(old, new)

old2 = """**本專案的程式碼是逐行原創的**（Kotlin 原生實作，非移植），
但這是**必須由一位人類審查者實際確認**的，
而不是我從記憶裡斷言的。"""

new2 = """**本專案的程式碼是逐行原創的**（Kotlin 原生實作，非移植），
但這是**必須由一位人類審查者實際確認**的，
而不是我從記憶裡斷言的。

**本輪已做的機械檢查**（不能取代人工逐行比對）：

- 樹內沒有 `third_party/`、`vendor/`、`external/` 目錄
- 沒有任何原始碼檔案帶有 GPL/AGPL 的授權頭
- `integrations/dhc/` 是**依照契約**的獨立實作，
  而且契約檔案本身不在這個 repository 裡

**剩下的是**：早期提交有沒有整檔匯入。這個要靠人工看 `git log`。"""
assert old2 in s, 'the QuietInbox section was not found'
s = s.replace(old2, new2)

io.open(path, 'w', encoding='utf-8').write(s)
print("the provenance document now has the verified findings")
