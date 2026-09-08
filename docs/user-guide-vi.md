# Pinboard - Hướng dẫn sử dụng

Ghim góp ý lên nhiều đoạn code, rồi bảo agent xử lý cả loạt.

Tài liệu này hướng dẫn cài đặt, kết nối agent, sử dụng hằng ngày, và xử lý khi có sự cố. Bản tiếng
Anh ở [user-guide.md](user-guide.md).

---

## 1. Pinboard sinh ra để làm gì

JetBrains IDE của bạn vốn đã có sẵn một MCP server, và nó đã có thể đưa vùng chọn hiện tại cho agent.
Nhưng kênh đó là đồng bộ và chỉ dùng một lần: bạn trỏ vào một chỗ, agent nhìn vào đó, xong là hết.

Review code không diễn ra như vậy. Bạn đọc một file, thấy năm vấn đề, và muốn ghi lại cả năm, đọc
tiếp, rồi mới giao cả loạt khi đã xong.

Pinboard bổ sung đúng phần còn thiếu đó:

- **Một hàng đợi.** Ghim bao nhiêu ghi chú tùy ý. Chưa gửi đi đâu cả.
- **Gom lô.** Agent lấy cả cụm góp ý trong một lần gọi, thay vì mỗi việc một lượt qua lại.
- **Luồng hội thoại.** Agent trả lời, đặt câu hỏi, và ghi lại nó đã làm gì, ngay cạnh đoạn code bạn
  đã ghim.
- **Không mất khi khởi động lại.** Đóng IDE, mở lại, hàng đợi và lịch sử vẫn còn nguyên.
- **Cờ stale.** Nếu code đã thay đổi sau khi ghim, agent được báo, kèm ảnh chụp code lúc ghim để
  định vị lại.

---

## 2. Điều kiện cần

| | |
|---|---|
| IDE | Bất kỳ JetBrains IDE nào, build **252 (2025.2)** trở lên - IntelliJ IDEA, PyCharm, WebStorm, GoLand... |
| Phiên bản | Cả Community lẫn Ultimate đều chạy được |
| Plugin đi kèm | Bắt buộc bật **MCP Server**. Plugin này có sẵn trong IDE, không cần cài riêng |
| Agent | Bất kỳ MCP client nào nói chuyện được với server của IDE. Claude Code cấu hình chỉ bằng một cú nhấp |
| Git | Không bắt buộc. Nếu project là repository Git, Pinboard ghi lại revision tại thời điểm ghim |

### Kiểm tra plugin MCP Server

Vào **Settings | Plugins | Installed**, tìm *MCP Server*. Nó phải tồn tại và đang được bật.

Nếu nó bị tắt, Pinboard sẽ không cài được. Pinboard khai báo phụ thuộc cứng vào plugin đó, vì không
có server ấy thì các tool không có chỗ nào để xuất hiện.

> **Lưu ý về phiên bản IDE.** Pinboard cố ý không đặt giới hạn phiên bản trên, để plugin vẫn chạy khi
> IDE lên phiên bản lớn mới. Đổi lại, một bản IDE tương lai có thể thay đổi thứ gì đó bên dưới. Nếu
> có trục trặc ngay sau khi cập nhật IDE, đây là nguyên nhân cần nghi ngờ đầu tiên.

---

## 3. Cài plugin

1. Vào **Settings | Plugins | Marketplace**.
2. Tìm **Pinboard**.
3. Bấm **Install**.
4. **Khởi động lại IDE** khi được hỏi.

Việc khởi động lại là bắt buộc, và không phải do plugin làm biếng. Pinboard đăng ký các MCP tool, mà
nạp nóng một phần sẽ để lại tình trạng tool window vẫn chạy trong khi các tool đã âm thầm biến mất -
đây là kiểu hỏng tệ nhất, vì nhìn bề ngoài mọi thứ vẫn bình thường. Bắt khởi động lại khiến việc cài
và cập nhật hoặc là xong hẳn, hoặc là không.

### Cài từ file

Nếu bạn có file `pinboard-<phiên bản>.zip`:

**Settings | Plugins**, bấm biểu tượng bánh răng, chọn **Install Plugin from Disk...**, chọn file
zip, rồi khởi động lại.

### Xác nhận đã cài xong

Sau khi khởi động lại, ở cạnh phải cửa sổ IDE phải xuất hiện nút tool window tên **Pinboard**. Mở nó
ra. Nếu thấy, tức là plugin đã nạp thành công.

---

## 4. Kết nối agent

Pinboard không tự chạy server riêng. Các tool của nó xuất hiện trên MCP server mà IDE vốn đã chạy,
nên bạn không phải viết tay bất kỳ cấu hình MCP nào.

1. Mở phần cài đặt MCP của IDE:
   - **2025.2**: Settings | Tools | **MCP Server**
   - **2026.x**: Settings | Tools | **Client Auto-Configuration**
2. Tìm client của bạn trong danh sách. Với **Claude Code**, một cú nhấp là IDE tự ghi cấu hình cho
   bạn.
3. **Khởi động lại agent** để nó nhận server mới.

Riêng với Claude Code, sau khi khởi động lại hãy chạy `/mcp` và kiểm tra server của IDE có trong danh
sách và đang kết nối.

### Bạn sẽ thấy gì

Khi agent đã gọi bất kỳ tool nào của Pinboard dù chỉ một lần, con chip ở góc trên bên phải tool window
sẽ đổi trạng thái. Xem [mục 8](#8-agent-đã-kết-nối-thật-chưa).

---

## 5. Dạy agent biết khi nào cần dùng

Các tool đã sẵn sàng ngay khi cài plugin, nhưng agent không có lý do gì để tự tìm đến chúng. Nó không
biết là có một hàng đợi tồn tại, cũng không biết khi nào nên kiểm tra. Đó là việc của skill: skill bảo
agent lấy góp ý theo lô, cách đọc cờ `stale`, và phải đóng mỗi mục bằng một bản tóm tắt mà bạn kiểm
chứng được.

### Cách A - một câu lệnh, dùng được cho mọi agent (khuyến nghị)

```bash
npx skills add trantruong-dev/pinboard
```

Đây là CLI [`skills`](https://github.com/vercel-labs/skills), trình cài đặt của hệ sinh thái
[Agent Skills](https://agentskills.io). Nó biết mỗi coding agent lưu skill ở đâu - Claude Code, Codex,
Cursor, OpenCode, Gemini CLI, GitHub Copilot, Cline, Continue, Zed, Junie, Windsurf và vài chục cái
khác - nên nó tự ghi skill vào đúng chỗ mà bạn không phải tra cứu gì. Lệnh sẽ hỏi bạn muốn cài cho
những agent nào.

Vài biến thể hữu ích:

```bash
npx skills add trantruong-dev/pinboard --list          # chỉ xem có gì, không cài
npx skills add trantruong-dev/pinboard -g              # cài toàn cục, dùng cho mọi project
npx skills add trantruong-dev/pinboard -g -a claude-code -y   # một agent, không hỏi gì
```

**Chú ý phạm vi cài.** Mặc định lệnh cài vào **project hiện tại** (`./.claude/skills/` và thư mục
tương ứng của các agent khác), nghĩa là nó sẽ được commit cùng repository và đồng đội của bạn cũng có.
Thêm `-g` để cài vào thư mục home, khi đó skill dùng được ở mọi nơi và không đụng vào repository nào.

Không phải cấu hình gì thêm. Skill có hiệu lực ngay lần khởi động kế tiếp của agent.

### Cách B - dùng plugin của Claude Code

Nếu bạn muốn quản lý qua hệ thống plugin của chính Claude Code:

```
/plugin marketplace add trantruong-dev/pinboard
/plugin install pinboard@trantruong-dev
/reload-plugins
```

Dòng đầu đăng ký repository này thành một plugin marketplace, dòng thứ hai cài skill từ đó, dòng thứ
ba kích hoạt mà không cần khởi động lại Claude Code. Sau này có phiên bản mới thì `/plugin` sẽ đề nghị
bạn cập nhật.

- Marketplace được đăng ký **theo người dùng**, nên bạn chỉ làm một lần, không phải làm lại cho từng
  project.
- `/plugin` cần một phiên bản Claude Code tương đối mới. Nếu lệnh không được nhận, hãy dùng cách A
  hoặc C.

### Cách C - copy file thủ công

Copy file [`skills/pinboard/SKILL.md`](../skills/pinboard/SKILL.md) trong repository này sang:

| | |
|---|---|
| macOS / Linux | `~/.claude/skills/pinboard/SKILL.md` |
| Windows | `%USERPROFILE%\.claude\skills\pinboard\SKILL.md` |

Tự tạo thư mục nếu chưa có. Cách này chạy được trên mọi phiên bản Claude Code.

---

## 5b. Các agent khác ngoài Claude Code

**Pinboard không hề gắn riêng với Claude.** Plugin gắn các tool của nó vào MCP server mà IDE vốn đã
chạy, nên bất kỳ MCP client nào kết nối được tới server đó đều nhận đủ bảy tool, và không phải cấu
hình gì thêm ở phía Pinboard. Skill chỉ là thứ tiện lợi để dạy agent biết *khi nào* cần dùng.

Với client nào cũng chỉ có hai bước: kết nối, rồi bảo nó khi nào cần xem.

### Bước 1 - kết nối client với IDE

Mở phần cài đặt MCP của IDE (**Settings | Tools | MCP Server** ở bản 2025.2, hoặc
**Settings | Tools | Client Auto-Configuration** ở bản 2026.x).

**Những client IDE tự cấu hình giúp bạn.** Ở bản 2025.2, plugin MCP Server đi kèm hỗ trợ sẵn:

- Claude Code
- Claude Desktop
- Cursor
- VS Code
- Windsurf

Chỉ cần một cú nhấp là IDE tự ghi file cấu hình cho client đó. Các bản IDE mới hơn có thể thêm client
khác, nên hãy tin vào danh sách hiển thị trên trang cài đặt hơn là danh sách ở đây.

**Mọi client còn lại.** Cũng trên trang đó có mục **Manual Client Configuration** với hai nút:

| Nút | Dùng khi nào |
|---|---|
| **Copy SSE Config** | Client của bạn nói MCP qua SSE. Nếu client hỗ trợ cả hai thì ưu tiên cách này |
| **Copy Stdio Config** | Client của bạn chỉ nói MCP qua stdio |

Dán khối cấu hình vừa copy vào nơi client lưu danh sách MCP server. Đây là định dạng cấu hình MCP tiêu
chuẩn, nên dán được vào Cline, Continue, Zed, Codex CLI, Gemini CLI, JetBrains Junie, hay bất kỳ client
tự viết nào nói được MCP.

**Nhớ khởi động lại client sau khi cấu hình.** Đa số client chỉ đọc cấu hình MCP lúc khởi động.

Nếu IDE hiện thông báo *"MCP clients detected"*, nghĩa là nó đã phát hiện một client trên máy bạn và
đang đề nghị cấu hình giúp - cũng chính là việc trên, chỉ khác là do IDE chủ động.

### Bước 2 - bảo client khi nào cần dùng các tool

**Thử câu lệnh một dòng trước đã.** Lệnh `npx skills add trantruong-dev/pinboard` ở
[mục 5](#5-dạy-agent-biết-khi-nào-cần-dùng) không chỉ dành cho Claude - nó hỗ trợ hàng chục agent và tự
ghi skill vào đúng thư mục mà từng agent đọc. Nếu agent của bạn nằm trong danh sách đó thì bạn xong
rồi, có thể bỏ qua phần còn lại của bước này.

Với agent mà nó chưa hỗ trợ thì làm thủ công. File skill là Markdown thuần: toàn bộ phần nằm dưới khối
frontmatter `---` là văn bản không phụ thuộc client, cứ copy phần thân đó vào file mà client của bạn
đọc làm hướng dẫn thường trực.

| Client | Nơi đặt hướng dẫn cho project |
|---|---|
| Cursor | `.cursor/rules/` |
| Windsurf | `.windsurf/rules/` |
| VS Code + GitHub Copilot | `.github/copilot-instructions.md` |
| Cline | `.clinerules/` |
| JetBrains Junie | `.junie/guidelines.md` |
| Codex CLI và ngày càng nhiều client khác | `AGENTS.md` ở thư mục gốc project |

Các đường dẫn này thay đổi theo phiên bản - nếu một cách không có tác dụng, hãy xem tài liệu của chính
client đó. Nội dung bạn dán vào thì trường hợp nào cũng như nhau.

**Nếu client của bạn không có file hướng dẫn nào cả** thì cũng không sao. Cứ nói thẳng trong khung chat
khi bạn muốn nó xử lý hàng đợi:

> *"Kiểm tra pinboard và làm hết những mục đang chờ."*

Các tool tự nó đã hiện ra cho agent thấy; phần hướng dẫn chỉ giúp bạn khỏi phải nhắc đi nhắc lại.

### Hai điều hay gây vướng với client không phải Claude

**Tên tool thường bị thêm tiền tố.** Client của bạn có thể hiển thị `feedback_list` thành
`mcp__idea__feedback_list`, `idea.feedback_list`, hoặc tương tự, tùy cách nó đặt namespace cho server.
Nếu agent báo không có `feedback_watch`, gần như chắc chắn là nó đang tìm đúng tên trần. Hãy bảo nó
khớp theo phần `feedback_`.

**`feedback_watch` chỉ thấy những gì bạn ghim *sau* khi nó được gọi.** Nó giữ kết nối mở để chờ mục
tiếp theo, và đó chính là thứ giúp gom lô mà không cần hỏi liên tục - nhưng những gì đã nằm sẵn trong
hàng đợi thì nó không nhìn thấy. Agent nào mở đầu bằng `feedback_watch` sẽ ngồi im như đang rảnh
trong khi backlog của bạn không ai đụng tới. `feedback_list` mới là lệnh đọc backlog, và đó là lý do
skill dặn agent bắt đầu từ đó rồi mới chuyển sang `watch`.

**Mỗi client cũng chịu chờ một khoảng khác nhau.** IDE sẵn sàng chờ vài phút, nhưng MCP client thường
bỏ cuộc trước và lệnh gọi bị mất. Vì vậy tham số `timeoutSeconds` của `feedback_watch` mặc định là 60
giây. Nếu client của bạn hết giờ sớm hơn:

- giảm `timeoutSeconds` cho vừa, hoặc
- bỏ hẳn `feedback_watch` và dùng `feedback_list`, vì lệnh này trả về ngay lập tức.

Hỏi định kỳ bằng `feedback_list` chỉ tốn thêm một lượt qua lại và chạy được trên mọi client.

### Kiểm tra xem đã chạy chưa

Với client nào thì cách kiểm tra cũng giống nhau: bảo nó gọi thử một tool bất kỳ của Pinboard, rồi nhìn
con chip trên tool window. Nếu chip báo **Agent active** thì client đã thông.

---

## 6. Sử dụng hằng ngày

### Ghim một vùng chọn

Bôi đen đoạn code, rồi làm một trong các cách:

- Nhấn **`Ctrl+Alt+Shift+F`** (**`Cmd+Alt+Shift+F`** trên macOS)
- Bấm nút nổi lên phía trên vùng chọn
- Chuột phải, chọn **Pin for Agent**

Một bong bóng mở ra ngay tại con trỏ. Gõ ghi chú rồi nhấn **`Ctrl+Enter`** (**`Cmd+Enter`**) để ghim.

- **Esc** để hủy.
- Bấm vào editor để đọc lại code thì **không** làm mất bong bóng - nó vẫn mở trong lúc bạn xem quanh.

### Ghim cả file

Chuột phải vào file trong khung **Project**, hoặc chuột phải vào **tab editor** của file, chọn
**Pin File for Agent**. Dùng cách này cho những ghi chú về cả file chứ không phải một dòng cụ thể.

### Sửa lại ghi chú đã ghim

Gõ sai một chữ, hay viết chưa rõ ý? Chừng nào mục đó còn ở trạng thái **pending**, chọn nó trong
tool window rồi làm một trong ba cách:

- Bấm **`F2`**
- Bấm nút **Edit** trên thanh công cụ
- Chuột phải vào dòng, chọn **Edit**

Đúng khung nhập đó mở lại, ghi chú cũ đã điền sẵn, kèm đoạn code bạn đã ghim. **`Ctrl+Enter`** để
lưu, **Esc** để bỏ qua.

Hai giới hạn, đều là cố ý:

- **Chỉ sửa được chữ.** Vùng code đã ghim và bản chụp lúc ghim giữ nguyên. Muốn trỏ ghi chú sang
  đoạn code khác thì xoá đi ghim lại.
- **Mục đã acknowledged thì khoá.** Một khi agent báo đã đọc, nó đang làm theo đúng những chữ nó
  đọc được. Sửa chữ dưới chân nó là cách chắc chắn nhất để hai bên làm theo hai chỉ dẫn khác nhau.
  Trường hợp đó hãy nhắn cho agent chứ đừng sửa pin.

Có một khe hẹp nên biết: agent có thể *đọc* một mục pending trước khi acknowledge nó. Nếu bạn sửa
ghi chú lúc agent đang chạy giữa chừng thì nó có thể đã cầm bản chữ cũ. Còn nếu bạn sửa đúng lúc nó
acknowledge, thay đổi sẽ bị từ chối và Pinboard báo cho bạn biết chứ không im lặng nuốt mất.

### Nhìn thấy pin ngay trong code

Một vùng đã ghim sẽ:

- được tô nền trong editor,
- có vạch đánh dấu ở thanh cuộn bên phải,
- có biểu tượng pin ở lề trái - bấm vào đó sẽ mở hàng đợi lên.

File nào còn góp ý chưa xử lý thì tab editor của nó được phủ một lớp màu nhạt, để bạn liếc qua là
biết file đang mở nào còn việc.

**Sửa code ở phía trên một pin thì pin sẽ trôi theo code**, chứ không bị báo stale. Chỉ khi chính
đoạn code đã ghim bị thay đổi thì mới thành stale.

### Làm việc trong tool window

Tool window **Pinboard** bên phải hiển thị hàng đợi dưới dạng thẻ, nhóm theo trạng thái.

| Thao tác | Cách làm |
|---|---|
| Nhảy về đoạn code đã ghim | Nhấp đúp vào thẻ, hoặc chọn thẻ rồi nhấn **Enter** |
| Gập hoặc mở một nhóm trạng thái | Bấm vào tiêu đề nhóm |
| Sửa ghi chú đang pending | Phím **F2**, nút trên thanh công cụ, hoặc menu chuột phải của thẻ |
| Xóa một mục | Phím **Del**, nút trên thanh công cụ, hoặc menu chuột phải của thẻ |
| Copy một mục dưới dạng Markdown | Nút **Copy** trên thanh công cụ, menu chuột phải của thẻ, chuột phải trong khung chi tiết khi không bôi đen gì, hoặc **Ctrl+C** khi danh sách đang được focus |
| Copy đúng phần đang bôi đen | Bôi đen rồi chọn **Copy Selection** trong menu chuột phải, hoặc **Ctrl+C** |
| Xóa hàng loạt việc đã xong | Menu **Clear**: *Clear resolved*, *Clear dismissed* |
| Xóa sạch | **Delete All** - có hỏi lại, vì không hoàn tác được |

Phía trên có một thanh cho biết đã xử lý được bao nhiêu phần hàng đợi. Biểu tượng tool window có một
chấm tròn khi còn mục đang chờ, nên bạn biết còn việc mà không cần mở panel ra.

Chọn một thẻ sẽ hiện khung chi tiết: nó trỏ vào đâu, code đã dịch chuyển chưa, nội dung ghi chú, đoạn
code tại thời điểm ghim, và toàn bộ hội thoại với agent. Mọi phần trong đó đều là văn bản chọn được
bình thường - bôi đen dòng tiêu đề, ghi chú, một tin nhắn trong luồng, hay đoạn code, rồi Ctrl+C là
copy đúng phần bạn vừa bôi đen. Đoạn code là một khung xem trực tiếp chứ không phải ô bị vô hiệu hóa:
nó hiện thành một khối cuộn được, và Ctrl+F tìm được chữ bên trong y như trong một editor bình
thường. Khối đó là bản ghi duy nhất về thứ đã thực sự được ghim một khi file đã thay đổi - đúng lúc
mà cảnh báo stale bảo bạn tin vào nó thay vì tin số dòng.

### Copy nguyên một pin

**Copy** đưa mục đang chọn lên clipboard thành một khối Markdown duy nhất - vị trí, trạng thái, thời
điểm ghim (kèm ghi chú stale hoặc file missing nếu có), ghi chú, đoạn code trong một khối code có
fence, và toàn bộ hội thoại. Dán thẳng vào khung chat với một agent không có quyền truy cập MCP vào
hàng đợi.

Có bốn cách để dùng, giống hệt Edit và Delete: nút trên thanh công cụ, menu chuột phải của thẻ, chuột
phải trong khung chi tiết, hoặc **Ctrl+C** khi danh sách đang được focus. Nút này bị vô hiệu khi chưa
chọn mục nào.

Copy không bao giờ lấy nhiều hơn thứ bạn yêu cầu. Bôi đen một phần ghi chú rồi bấm chuột phải, mục
trong menu hiện là **Copy Selection** và chỉ copy đúng phần đó. Bấm chuột phải khi không bôi đen gì
thì mục đó là **Copy** và copy nguyên pin. Ctrl+C cũng theo đúng quy tắc này: khi danh sách đang
focus thì copy cả mục, khi con trỏ nằm trong ghi chú hoặc đoạn code thì copy phần bạn bôi đen ở đó -
vì khung chi tiết không nằm trong danh sách, nên vùng chọn của riêng nó thắng.

---

## 7. Vòng đời của một mục

Mỗi mục luôn ở đúng một trong bốn trạng thái.

| Trạng thái | Ý nghĩa |
|---|---|
| **Pending** | Bạn vừa ghim. Chưa ai xem |
| **Acknowledged** | Agent đã đọc và đang làm. **Chưa xong** |
| **Resolved** | Agent đã làm xong và để lại tóm tắt việc đã làm |
| **Dismissed** | Agent quyết định không xử lý, và để lại lý do |

Nhóm resolved và dismissed mặc định được gập lại, vì đó là lịch sử, để mở ra sẽ đẩy các mục đang chờ
ra khỏi tầm nhìn.

**Acknowledged không có nghĩa là xong.** Nếu agent khởi động lại giữa chừng, các mục acknowledged
chính là những việc nó đã bắt đầu; một agent làm đúng sẽ quay lại làm tiếp.

**Pending cũng là cửa sổ để bạn đổi ý về câu chữ.** Mốc acknowledge là hạn chót: sau đó ghi chú
bị khoá. Xem mục *Sửa lại ghi chú đã ghim* ở trên.

### Khi code thay đổi bên dưới một pin

- **Stale** - chính đoạn code đã ghim bị thay đổi sau khi ghim. Số dòng không còn tin được nữa. Agent
  được báo điều này, kèm ảnh chụp code lúc ghim và tên symbol bao quanh, để tìm xem đoạn code đó giờ
  nằm ở đâu.
- **File missing** - file đã bị đổi tên, di chuyển, hoặc xóa. Agent được yêu cầu nói thẳng ra thay vì
  bịa ra một vị trí.

Trong cả hai trường hợp, ảnh chụp code vẫn là căn cứ chính. Không mất gì cả.

---

## 8. Agent đã kết nối thật chưa

Con chip ở góc trên bên phải tool window trả lời câu này, còn dòng chạy dọc phía dưới cho biết agent
gọi tool lần cuối lúc nào. Mở **Log** ngay cạnh đó để xem danh sách các lệnh gọi.

| Chip | Nghĩa là gì | Cần làm gì |
|---|---|---|
| **Agent active** | Có lệnh gọi tool trong mười phút gần đây | Không cần làm gì |
| **Idle** | Agent từng gọi, nhưng gần đây thì không | Bình thường, giữa hai tác vụ |
| **Waiting for agent** | Mọi thứ đã sẵn sàng; chưa có ai gọi | Kiểm tra agent có đang chạy và đã cấu hình server của IDE chưa |
| **Tools not registered** | Plugin nạp được nhưng thiếu các MCP tool | Khởi động lại IDE |

Con chip cố ý không bao giờ nói "đã kết nối". Pinboard chạy nhờ MCP server của IDE chứ không tự chạy
server riêng, nên nó không thể hỏi xem có client nào đang gắn vào hay không. Nó chỉ báo những lệnh gọi
thực sự đã đến, và chỉ nói đúng chừng đó.

---

## 9. Các tool mà agent nhận được

| Tool | Chức năng |
|---|---|
| `feedback_list` | Hàng đợi hiện tại. Mặc định lấy pending và acknowledged |
| `feedback_watch` | Chặn lại chờ những mục được ghim *sau* lời gọi, rồi trả về cả lô |
| `feedback_acknowledge` | Đánh dấu đã xem. Nhận cả lô trong một lần gọi |
| `feedback_resolve` | Đóng một mục, bắt buộc kèm tóm tắt việc đã làm |
| `feedback_dismiss` | Đóng một mục, bắt buộc kèm lý do không xử lý |
| `feedback_reply` | Thêm câu hỏi hoặc ghi chú vào luồng, không đổi trạng thái |
| `feedback_clear_resolved` | Xóa các mục đã resolved hoặc dismissed |

MCP client của bạn có thể hiển thị các tool này kèm tiền tố lấy từ tên server, ví dụ
`mcp__idea__feedback_list`. Đó là bình thường.

**Agent không thể tạo góp ý, và không thể xóa bất cứ thứ gì còn pending hoặc acknowledged.** Ranh giới
đó là cố ý. Hàng đợi là bản ghi của bạn về những gì bạn đã yêu cầu, và một agent có thể âm thầm dọn
sạch phần việc nó chưa làm xong sẽ hủy mất bản ghi duy nhất đó.

---

## 10. Dữ liệu của bạn đi đâu

Không đi đâu cả. Plugin không gọi mạng và không thu thập telemetry.

Hàng đợi được lưu dạng JSON trong thư mục system của IDE
(`PathManager.getSystemPath()/pinboard/`), mỗi project một file, tên file lấy từ hash đường dẫn gốc
của project. Nó nằm **ngoài repository**, nên không bao giờ lọt vào commit.

**File đó chứa mã nguồn nguyên văn** - ảnh chụp của mọi thứ bạn đã ghim - nên hãy giữ thư mục đó cẩn
thận như chính repository.

MCP server phục vụ các tool này là server của IDE, chỉ lắng nghe trên localhost.

### Mỗi project một hàng đợi

Các hàng đợi không bao giờ lẫn vào nhau. Mở mười project cùng lúc thì agent làm việc trong project nào
chỉ thấy góp ý của project đó.

---

## 11. Xử lý sự cố

**Không thấy tool window Pinboard đâu.**
Plugin chưa nạp. Vào **Settings | Plugins | Installed** kiểm tra Pinboard đang bật, và **MCP Server**
cũng đang bật. Rồi khởi động lại IDE.

**Chip báo "Tools not registered".**
Plugin đã nạp nhưng các MCP tool chưa đăng ký được. Khởi động lại IDE. Nếu khởi động lại vẫn vậy thì
nhiều khả năng plugin MCP Server đang bị tắt.

**Chip cứ đứng ở "Waiting for agent".**
Phía IDE không có vấn đề gì - chỉ là chưa có ai gọi. Kiểm tra agent có đang chạy không, đã cấu hình
MCP server của IDE chưa, và sau khi cấu hình đã khởi động lại agent chưa. Trong Claude Code, lệnh
`/mcp` liệt kê các server mà nó nhìn thấy.

**Agent nói không tìm thấy `feedback_watch`.**
Client thường hiển thị tool kèm tiền tố, nên tên thật có thể là `mcp__idea__feedback_watch`. Agent nào
tìm đúng tên trần mà không thấy thì phải tìm lại theo phần đuôi. Skill ở
[mục 5](#5-dạy-agent-biết-khi-nào-cần-dùng) dặn agent làm đúng như vậy.

**Agent báo `HTTP 404: Session not found`.**
Một lượt làm việc dài của agent đã sống lâu hơn phiên MCP của IDE. Đây là server có sẵn của IDE, không
phải của Pinboard. Kết nối lại (`/mcp` trong Claude Code) rồi bảo agent thử lại. Không mất gì cả - mục
đã acknowledged vẫn nằm đó chờ được resolve.

**Một pin báo "file missing" nhưng file vẫn nằm đó.**
File đã bị di chuyển hoặc đổi tên sau khi ghim. Pinboard lưu đường dẫn tại thời điểm ghim. Ảnh chụp
code vẫn còn nguyên, nên ghi chú vẫn đọc được và agent được yêu cầu định vị lại thay vì đoán bừa.

**Hai cửa sổ IDE trên cùng một repository.**
Chúng dùng chung một hàng đợi, đây là chủ ý, và tin nhắn trong luồng từ cả hai phía được gộp lại chứ
không bị mất. Nhưng mọi thứ còn lại của một mục - nội dung ghi chú, trạng thái - vẫn theo nguyên tắc
ghi sau đè ghi trước. Sửa cùng một mục từ hai cửa sổ cùng lúc thì một bên sẽ mất. Nếu bạn làm việc kiểu
này, mỗi lúc chỉ sửa một mục từ một cửa sổ.

---

## 12. Một ví dụ trọn vẹn

1. Bạn đang review một pull request trong IDE. Bạn thấy bốn vấn đề nằm ở ba file.
2. Bạn bôi đen từng chỗ và nhấn `Ctrl+Alt+Shift+F`, mỗi lần gõ một ghi chú ngắn. Bốn thẻ xuất hiện
   dưới nhóm **Pending**. Chưa có gì được gửi đi đâu cả.
3. Bạn bảo agent: *"xử lý hết pinboard đi"*.
4. Agent gọi `feedback_list`, nhận cả bốn mục trong một lô, rồi gọi `feedback_acknowledge` với cả bốn
   id. Trong tool window, chúng chuyển sang **Acknowledged** và thanh tiến độ nhích lên.
5. Với từng mục, agent đọc ghi chú của bạn và ảnh chụp code, thực hiện thay đổi, rồi gọi
   `feedback_resolve` kèm tóm tắt đúng những gì nó đã làm.
6. Sau đó agent gọi `feedback_watch` và nằm chờ ở đó cho những gì bạn ghim tiếp theo.
7. Bạn đọc các bản tóm tắt trong khung chi tiết, ngay cạnh đoạn code đã ghim. Một mục không đúng ý
   bạn, nên bạn ghim thêm một góp ý nữa - và vì agent đang nằm trong `feedback_watch`, nó tự nhận
   mục đó luôn.
8. Khi đã hài lòng, **Clear | Clear resolved** dọn sạch phần việc đã xong.

---

## Ủng hộ plugin

Pinboard miễn phí và sẽ luôn miễn phí. Nếu nó giúp bạn tiết kiệm thời gian, bạn có thể
[mời tôi một ly cà phê](https://buymeacoffee.com/trantruong.dev).

---

## Tham khảo

- Mã nguồn và báo lỗi: <https://github.com/trantruong-dev/pinboard>
- Ủng hộ plugin: <https://buymeacoffee.com/trantruong.dev>
- Nhật ký thay đổi: [CHANGELOG.md](../CHANGELOG.md)
- Giấy phép: Apache-2.0. Một phần thiết kế giao diện và tương tác được phỏng theo
  [Marginalia](https://github.com/borgand/marginalia) (MIT)
