package com.questtick.i18n


private val additionalExactTranslations =
    mapOf(
        "126 邮箱" to Translation("126 Mail", "126メール", "126 메일"),
        "163 邮箱" to Translation("163 Mail", "163メール", "163 메일"),
        "Outlook 邮箱" to Translation("Outlook", "Outlook", "Outlook"),
        "QQ 邮箱" to Translation("QQ Mail", "QQメール", "QQ 메일"),
        "Yeah 邮箱" to Translation("Yeah Mail", "Yeahメール", "Yeah 메일"),
        "邮箱" to Translation("Email", "メール", "이메일"),
        "自定义邮箱" to Translation("Custom email provider", "カスタムメール", "사용자 지정 이메일"),
        "Cookie 失效后可自动刷新，无需重新扫码" to Translation("Refreshes automatically when the Cookie expires; no new scan required", "Cookie失効時に自動更新します。再スキャンは不要です", "Cookie가 만료되면 자동으로 갱신되며 다시 스캔할 필요가 없습니다"),
        "Token 失效后可自动续期，无需重新扫码" to Translation("Renews automatically when the Token expires; no new scan required", "Token失効時に自動更新します。再スキャンは不要です", "Token이 만료되면 자동으로 갱신되며 다시 스캔할 필요가 없습니다"),
        "MIHOYO_CLOUD_DEVICE_ID（可选）" to Translation("MIHOYO_CLOUD_DEVICE_ID (optional)", "MIHOYO_CLOUD_DEVICE_ID（任意）", "MIHOYO_CLOUD_DEVICE_ID(선택 사항)"),
        "MIHOYO_DEVICE_ID（可选）" to Translation("MIHOYO_DEVICE_ID (optional)", "MIHOYO_DEVICE_ID（任意）", "MIHOYO_DEVICE_ID(선택 사항)"),
        "MYS_DEVICE_ID（可选）" to Translation("MYS_DEVICE_ID (optional)", "MYS_DEVICE_ID（任意）", "MYS_DEVICE_ID(선택 사항)"),
        "ACT_ID 失效自动刷新" to Translation("Refresh ACT_ID automatically when it expires", "ACT_ID失効時に自動更新", "ACT_ID 만료 시 자동 새로고침"),
        "下载进度" to Translation("Download progress", "ダウンロード進捗", "다운로드 진행률"),
        "二维码已过期，请点击刷新" to Translation("The QR code has expired. Tap refresh.", "QRコードの有効期限が切れました。更新してください", "QR 코드가 만료되었습니다. 새로고침을 누르세요"),
        "云原神扫码✓" to Translation("Cloud Genshin QR ✓", "クラウド原神QR ✓", "클라우드 원신 QR ✓"),
        "云崩铁扫码✓" to Translation("Cloud Honkai: Star Rail QR ✓", "クラウド崩壊：スターレイルQR ✓", "클라우드 붕괴: 스타레일 QR ✓"),
        "米游社扫码✓" to Translation("Miyoushe QR ✓", "米游社QR ✓", "미요우서 QR ✓"),
        "云备用" to Translation("Cloud backup", "クラウド予備", "클라우드 백업"),
        "云游戏" to Translation("Cloud gaming", "クラウドゲーム", "클라우드 게임"),
        "仅使用本次 Cookie，失效后需重新扫码" to Translation("Use this Cookie only; scan again after it expires", "今回のCookieのみ使用し、失効後は再スキャンが必要です", "이번 Cookie만 사용하며 만료되면 다시 스캔해야 합니다"),
        "仅使用本次 Token，失效后需重新扫码" to Translation("Use this Token only; scan again after it expires", "今回のTokenのみ使用し、失効後は再スキャンが必要です", "이번 Token만 사용하며 만료되면 다시 스캔해야 합니다"),
        "任务队列" to Translation("Task queue", "タスクキュー", "작업 대기열"),
        "作者不对因使用本项目产生的任何问题承担责任。" to Translation("The author assumes no liability for any issue arising from use of this project.", "本プロジェクトの利用によって生じたいかなる問題についても、作者は責任を負いません。", "이 프로젝트 사용으로 발생하는 어떠한 문제에 대해서도 작성자는 책임을 지지 않습니다."),
        "例如：主号、小号" to Translation("For example: Main, Alt", "例：メイン、サブ", "예: 본계정, 부계정"),
        "保存云游戏扫码结果失败" to Translation("Failed to save cloud gaming QR login", "クラウドゲームQRログインの保存に失敗しました", "클라우드 게임 QR 로그인 저장 실패"),
        "保存失败" to Translation("Save failed", "保存に失敗しました", "저장 실패"),
        "保存失败：无法打开目标文件" to Translation("Save failed: Unable to open the destination file", "保存に失敗しました：保存先ファイルを開けません", "저장 실패: 대상 파일을 열 수 없습니다"),
        "保存扫码登录结果失败" to Translation("Failed to save QR login", "QRログインの保存に失敗しました", "QR 로그인 저장 실패"),
        "关闭「自动管理」→ 手动打开全部三项开关" to Translation("Disable “Automatic management”, then enable all three switches manually", "「自動管理」を無効にし、3つの項目をすべて手動で有効にしてください", "‘자동 관리’를 끈 다음 세 항목을 모두 수동으로 켜세요"),
        "切换筛选条件可查看其它日志" to Translation("Change the filter to view other logs", "フィルターを変更すると他のログを表示できます", "필터를 변경하여 다른 로그를 볼 수 있습니다"),
        "删除失败" to Translation("Delete failed", "削除に失敗しました", "삭제 실패"),
        "删除并重新添加" to Translation("Delete and add again", "削除して再追加", "삭제 후 다시 추가"),
        "删除损坏账号数据失败" to Translation("Failed to delete corrupted account data", "破損したアカウントデータを削除できませんでした", "손상된 계정 데이터 삭제 실패"),
        "删除无法恢复的账号" to Translation("Delete unrecoverable account", "復元できないアカウントを削除", "복구할 수 없는 계정 삭제"),
        "删除账号" to Translation("Delete account", "アカウントを削除", "계정 삭제"),
        "刷新中…" to Translation("Refreshing…", "更新中…", "새로고침 중…"),
        "刷新失败" to Translation("Refresh failed", "更新に失敗しました", "새로고침 실패"),
        "刷新完成" to Translation("Refresh complete", "更新完了", "새로고침 완료"),
        "可能导致账号风控，账号较多时请谨慎开启。" to Translation("This may trigger account risk controls. Use with care when you have many accounts.", "アカウントのリスク制御を誘発する可能性があります。多数のアカウントでは慎重に使用してください。", "계정 보안 제한이 발생할 수 있습니다. 계정이 많다면 신중하게 사용하세요."),
        "后台已暂停：需完成短信验证" to Translation("Background sign-in paused: SMS verification required", "バックグラウンド受取を一時停止：SMS認証が必要です", "백그라운드 출석 일시 중지: SMS 인증 필요"),
        "后台已暂停：需完成验证码" to Translation("Background sign-in paused: Verification required", "バックグラウンド受取を一時停止：認証が必要です", "백그라운드 출석 일시 중지: 인증 필요"),
        "后台已暂停：需重新登录" to Translation("Background sign-in paused: Sign in again", "バックグラウンド受取を一時停止：再ログインが必要です", "백그라운드 출석 일시 중지: 다시 로그인 필요"),
        "后台签到已暂停" to Translation("Background sign-in is paused", "バックグラウンド受取は一時停止中です", "백그라운드 출석이 일시 중지되었습니다"),
        "启用账号未配置 Cookie 或 Token，请先完善账号信息" to Translation("Enabled accounts have no Cookie or Token. Complete the account settings first.", "有効なアカウントにCookieまたはTokenがありません。先にアカウント設定を完了してください。", "사용 중인 계정에 Cookie 또는 Token이 없습니다. 먼저 계정 설정을 완료하세요."),
        "处理" to Translation("Resolve", "対応", "처리"),
        "多个邮箱用英文逗号分隔" to Translation("Separate multiple addresses with commas", "複数のアドレスは半角カンマで区切ってください", "여러 주소는 쉼표로 구분하세요"),
        "安装包已下载，是否现在安装更新？" to Translation("The APK is ready. Install the update now?", "APKのダウンロードが完了しました。今すぐアップデートしますか？", "APK 다운로드가 완료되었습니다. 지금 업데이트를 설치할까요?"),
        "定时签到在后台被系统杀掉的概率已大幅降低。" to Translation("Scheduled sign-in is much less likely to be terminated in the background.", "自動受取がバックグラウンドで終了されにくくなります。", "예약 출석이 백그라운드에서 종료될 가능성이 크게 줄어듭니다."),
        "将应用加入电池优化白名单后，定时签到更准点、更不易被系统清理。" to Translation("Exempting the app from battery optimization improves schedule accuracy and background reliability.", "バッテリー最適化の対象外にすると、実行時刻の精度とバックグラウンド動作が改善します。", "앱을 배터리 최적화에서 제외하면 예약 정확도와 백그라운드 안정성이 향상됩니다."),
        "就绪" to Translation("Ready", "準備完了", "준비됨"),
        "已删除无法恢复的账号数据" to Translation("Unrecoverable account data deleted", "復元できないアカウントデータを削除しました", "복구할 수 없는 계정 데이터를 삭제했습니다"),
        "已加入电池优化白名单" to Translation("Battery optimization exemption enabled", "バッテリー最適化の対象外です", "배터리 최적화 제외됨"),
        "已处理，恢复" to Translation("Resolved — restore", "対応済み・復元", "처리됨 · 복원"),
        "已开启 DEBUG 级别日志" to Translation("DEBUG logging is enabled", "DEBUGログが有効です", "DEBUG 로그가 활성화되었습니다"),
        "已恢复该账号的后台签到" to Translation("Background sign-in restored for this account", "このアカウントのバックグラウンド受取を再開しました", "이 계정의 백그라운드 출석을 복원했습니다"),
        "已扫码但尚未确认" to Translation("Scanned, awaiting confirmation", "スキャン済み・確認待ち", "스캔됨 · 확인 대기 중"),
        "已扫码，请在手机上确认" to Translation("Scanned. Confirm on your phone.", "スキャンしました。スマートフォンで確認してください。", "스캔되었습니다. 휴대전화에서 확인하세요."),
        "已重新加入邮件发送队列" to Translation("Email added back to the delivery queue", "メールを送信キューに再追加しました", "이메일을 전송 대기열에 다시 추가했습니다"),
        "已阻断" to Translation("Blocked", "ブロック済み", "차단됨"),
        "应用不会导出密文；删除后可重新登录添加。该操作不可恢复。" to Translation("Encrypted data is never exported. You can add the account again by signing in. This action cannot be undone.", "暗号化データはエクスポートされません。削除後は再ログインして追加できます。この操作は元に戻せません。", "암호화된 데이터는 내보내지 않습니다. 삭제 후 다시 로그인하여 추가할 수 있습니다. 이 작업은 되돌릴 수 없습니다."),
        "应用配置" to Translation("App configuration", "アプリ設定", "앱 설정"),
        "建议加入电池优化白名单" to Translation("Battery optimization exemption recommended", "バッテリー最適化の対象外にすることを推奨", "배터리 최적화 제외 권장"),
        "建议加入白名单" to Translation("Exemption recommended", "対象外設定を推奨", "제외 설정 권장"),
        "快速选择 SMTP 服务商" to Translation("Choose an SMTP provider", "SMTPプロバイダーを選択", "SMTP 제공업체 선택"),
        "恢复后台签到失败" to Translation("Failed to restore background sign-in", "バックグラウンド受取の再開に失敗しました", "백그라운드 출석 복원 실패"),
        "找到本应用 → 耗电保护 → 允许后台运行" to Translation("Find this app → Battery protection → Allow background activity", "本アプリ → バッテリー保護 → バックグラウンド動作を許可", "이 앱 찾기 → 배터리 보호 → 백그라운드 실행 허용"),
        "操作失败" to Translation("Operation failed", "操作に失敗しました", "작업 실패"),
        "放弃" to Translation("Discard", "破棄", "버리기"),
        "放弃修改？" to Translation("Discard changes?", "変更を破棄しますか？", "변경 사항을 버릴까요?"),
        "无法保存" to Translation("Unable to save", "保存できません", "저장할 수 없음"),
        "无法识别的账号" to Translation("Unrecognized account", "認識できないアカウント", "인식할 수 없는 계정"),
        "日志导出失败" to Translation("Log export failed", "ログのエクスポートに失敗しました", "로그 내보내기 실패"),
        "日志读取失败" to Translation("Log loading failed", "ログの読み込みに失敗しました", "로그 불러오기 실패"),
        "时" to Translation("Hour", "時", "시"),
        "分" to Translation("Minute", "分", "분"),
        "是否现在下载并安装更新？" to Translation("Download and install the update now?", "今すぐアップデートをダウンロードしてインストールしますか？", "지금 업데이트를 다운로드하고 설치할까요?"),
        "显示/隐藏" to Translation("Show/Hide", "表示／非表示", "표시/숨기기"),
        "暂无更新公告。" to Translation("No release notes are available.", "リリースノートはありません。", "업데이트 안내가 없습니다."),
        "更新公告" to Translation("Release notes", "リリースノート", "업데이트 안내"),
        "未命名账号" to Translation("Unnamed account", "名称未設定のアカウント", "이름 없는 계정"),
        "未找到 APK 下载地址" to Translation("APK download URL not found", "APKのダウンロードURLが見つかりません", "APK 다운로드 주소를 찾을 수 없습니다"),
        "未找到匹配日志" to Translation("No matching logs", "一致するログがありません", "일치하는 로그가 없습니다"),
        "未知错误" to Translation("Unknown error", "不明なエラー", "알 수 없는 오류"),
        "本地凭证无法解密，请删除后重新登录" to Translation("Local credentials cannot be decrypted. Delete the account and sign in again.", "ローカル認証情報を復号できません。削除して再ログインしてください。", "로컬 인증 정보를 복호화할 수 없습니다. 계정을 삭제하고 다시 로그인하세요."),
        "本地日志暂时无法读取，请重试" to Translation("Local logs are temporarily unavailable. Try again.", "ローカルログを読み込めません。再試行してください。", "로컬 로그를 불러올 수 없습니다. 다시 시도하세요."),
        "本地记录暂时无法读取，请重试" to Translation("Local records are temporarily unavailable. Try again.", "ローカル履歴を読み込めません。再試行してください。", "로컬 기록을 불러올 수 없습니다. 다시 시도하세요."),
        "本地账号暂时无法读取，请重试" to Translation("Local accounts are temporarily unavailable. Try again.", "ローカルアカウントを読み込めません。再試行してください。", "로컬 계정을 불러올 수 없습니다. 다시 시도하세요."),
        "本地账号格式已损坏，请删除后重新登录" to Translation("The local account data is corrupted. Delete the account and sign in again.", "ローカルアカウントデータが破損しています。削除して再ログインしてください。", "로컬 계정 데이터가 손상되었습니다. 계정을 삭제하고 다시 로그인하세요."),
        "本次扫码未返回自动刷新凭证，只能保存普通 Cookie。" to Translation("This QR login did not return renewable credentials; only a standard Cookie can be saved.", "今回のQRログインでは自動更新用の認証情報が返されなかったため、通常のCookieのみ保存できます。", "이번 QR 로그인에서 자동 갱신 인증 정보가 반환되지 않아 일반 Cookie만 저장할 수 있습니다."),
        "本项目仅供学习、交流和测试使用。" to Translation("This project is intended solely for learning, communication, and testing.", "本プロジェクトは学習・交流・テストのみを目的としています。", "이 프로젝트는 학습, 교류 및 테스트 목적으로만 제공됩니다."),
        "检测到Root阻断签到" to Translation("Sign-in blocked because Root was detected", "Rootを検出したため受取をブロックしました", "루팅이 감지되어 출석이 차단되었습니다"),
        "正在加载更多记录…" to Translation("Loading more records…", "履歴をさらに読み込み中…", "기록 더 불러오는 중…"),
        "正在执行 Root 与运行环境检测" to Translation("Checking Root and the runtime environment", "Rootと実行環境を確認中", "루팅 및 실행 환경 검사 중"),
        "正在检查运行环境" to Translation("Checking the runtime environment", "実行環境を確認中", "실행 환경 검사 중"),
        "正在获取云游戏 Token…" to Translation("Getting cloud gaming Token…", "クラウドゲームTokenを取得中…", "클라우드 게임 Token 가져오는 중…"),
        "正在连接…" to Translation("Connecting…", "接続中…", "연결 중…"),
        "没有可执行任务" to Translation("No tasks to run", "実行できるタスクがありません", "실행할 작업이 없습니다"),
        "没有启用的账号，请先启用账号后再签到" to Translation("No accounts are enabled. Enable an account before signing in.", "有効なアカウントがありません。アカウントを有効にしてから実行してください。", "사용 중인 계정이 없습니다. 계정을 활성화한 후 출석하세요."),
        "清除搜索" to Translation("Clear search", "検索を消去", "검색 지우기"),
        "清除搜索关键词或切换筛选条件后重试" to Translation("Clear the search query or change the filter and try again", "検索語を消去するかフィルターを変更して再試行してください", "검색어를 지우거나 필터를 변경한 후 다시 시도하세요"),
        "点击上方「添加账号」开始配置" to Translation("Tap “Add account” above to begin", "上の「アカウントを追加」をタップして設定を開始してください", "위의 ‘계정 추가’를 눌러 설정을 시작하세요"),
        "生成二维码失败" to Translation("Failed to generate QR code", "QRコードの生成に失敗しました", "QR 코드 생성 실패"),
        "用户已取消扫码" to Translation("QR login cancelled by user", "ユーザーがQRログインをキャンセルしました", "사용자가 QR 로그인을 취소했습니다"),
        "申请忽略电池优化" to Translation("Request battery optimization exemption", "バッテリー最適化の対象外設定を開く", "배터리 최적화 제외 요청"),
        "登录成功！请选择是否保持登录状态" to Translation("Login successful. Choose whether to keep this login active.", "ログインしました。ログイン状態を維持するか選択してください。", "로그인 성공. 로그인 상태를 유지할지 선택하세요."),
        "端口 465 使用 SSL，587 使用 STARTTLS。" to Translation("Port 465 uses SSL; port 587 uses STARTTLS.", "ポート465はSSL、587はSTARTTLSを使用します。", "465 포트는 SSL, 587 포트는 STARTTLS를 사용합니다."),
        "签" to Translation("GO", "受取", "출석"),
        "签到任务已取消" to Translation("Sign-in task cancelled", "受取タスクをキャンセルしました", "출석 작업이 취소되었습니다"),
        "签到正在运行中，请稍后再试" to Translation("Sign-in is already running. Try again later.", "受取処理を実行中です。しばらくしてから再試行してください。", "출석이 이미 진행 중입니다. 잠시 후 다시 시도하세요."),
        "签到结束" to Translation("Sign-in finished", "受取終了", "출석 종료"),
        "签到结果待确认：为避免重复请求，今日不会自动重试" to Translation("Sign-in result is uncertain. To avoid duplicate requests, it will not retry automatically today.", "受取結果が不明です。重複リクエストを避けるため、本日は自動再試行しません。", "출석 결과가 불확실합니다. 중복 요청을 피하기 위해 오늘은 자동으로 다시 시도하지 않습니다."),
        "米游社" to Translation("Miyoushe", "米游社", "미요우서"),
        "粘贴 Cookie，或使用扫码登录" to Translation("Paste a Cookie or use QR code login", "Cookieを貼り付けるか、QRコードログインを使用してください", "Cookie를 붙여넣거나 QR 코드 로그인을 사용하세요"),
        "粘贴 Token，或使用扫码登录" to Translation("Paste a Token or use QR code login", "Tokenを貼り付けるか、QRコードログインを使用してください", "Token을 붙여넣거나 QR 코드 로그인을 사용하세요"),
        "继续编辑" to Translation("Keep editing", "編集を続ける", "계속 편집"),
        "网络波动，正在继续重试…" to Translation("Network issue detected. Retrying…", "ネットワークが不安定です。再試行中…", "네트워크가 불안정합니다. 다시 시도 중…"),
        "获取 Token 失败" to Translation("Failed to get Token", "Tokenの取得に失敗しました", "Token 가져오기 실패"),
        "获取云游戏 Token 失败" to Translation("Failed to get cloud gaming Token", "クラウドゲームTokenの取得に失敗しました", "클라우드 게임 Token 가져오기 실패"),
        "记录读取失败" to Translation("Record loading failed", "履歴の読み込みに失敗しました", "기록 불러오기 실패"),
        "请先在手机米游社中点击确认登录。确定要退出当前扫码流程吗？" to Translation("Confirm the login in Miyoushe on your phone first. Exit this QR login flow?", "先にスマートフォンの米游社でログインを確認してください。このQRログインを終了しますか？", "먼저 휴대전화 미요우서에서 로그인을 확인하세요. 현재 QR 로그인을 종료할까요?"),
        "请先在设置中启用并完善邮件配置" to Translation("Enable and complete email settings first", "先に設定でメール通知を有効にし、必要項目を入力してください", "먼저 설정에서 이메일을 활성화하고 구성을 완료하세요"),
        "请先添加账号后再签到" to Translation("Add an account before signing in", "アカウントを追加してから実行してください", "계정을 추가한 후 출석하세요"),
        "请前往「账号」页面添加" to Translation("Go to Accounts to add one", "「アカウント」ページで追加してください", "‘계정’ 화면에서 추가하세요"),
        "请在遵守相关服务条款与法规的前提下低调使用，若不同意请停止使用。" to Translation("Use this app responsibly and in compliance with applicable terms and laws. Stop using it if you do not agree.", "関連する利用規約と法令を遵守して責任をもって使用し、同意できない場合は使用を中止してください。", "관련 서비스 약관과 법률을 준수하여 책임감 있게 사용하세요. 동의하지 않으면 사용을 중단하세요."),
        "请点击下方按钮刷新" to Translation("Tap the button below to refresh", "下のボタンをタップして更新してください", "아래 버튼을 눌러 새로고침하세요"),
        "请至少填写米游社 Cookie、云游戏 Token 或保留扫码登录凭证中的一项" to Translation("Provide at least a Miyoushe Cookie, a cloud gaming Token, or renewable QR login credentials", "米游社Cookie、クラウドゲームToken、自動更新可能なQRログイン認証情報のいずれかを入力してください", "미요우서 Cookie, 클라우드 게임 Token 또는 갱신 가능한 QR 로그인 인증 정보 중 하나 이상을 입력하세요"),
        "请至少选择一个需要签到的游戏" to Translation("Select at least one game", "ゲームを1つ以上選択してください", "게임을 하나 이상 선택하세요"),
        "调试日志" to Translation("Debug logging", "デバッグログ", "디버그 로그"),
        "账号状态与每日签到同步" to Translation("Account status and daily sign-in sync", "アカウント状態と毎日の受取を同期", "계정 상태와 일일 출석 동기화"),
        "账号读取失败" to Translation("Account loading failed", "アカウントの読み込みに失敗しました", "계정 불러오기 실패"),
        "运行日志" to Translation("Runtime logs", "実行ログ", "실행 로그"),
        "退出云游戏登录失败" to Translation("Failed to sign out of cloud gaming", "クラウドゲームからログアウトできませんでした", "클라우드 게임 로그아웃 실패"),
        "退出后会保留当前 Cookie，但无法自动刷新，失效后需要重新扫码登录。" to Translation("The current Cookie will remain, but it cannot refresh automatically. Scan again after it expires.", "現在のCookieは保持されますが、自動更新できません。失効後は再度QRログインしてください。", "현재 Cookie는 유지되지만 자동 갱신되지 않습니다. 만료되면 다시 QR 로그인하세요."),
        "退出后会保留当前 Token，但无法自动续期，失效后需要重新扫码登录。" to Translation("The current Token will remain, but it cannot renew automatically. Scan again after it expires.", "現在のTokenは保持されますが、自動更新できません。失効後は再度QRログインしてください。", "현재 Token은 유지되지만 자동 갱신되지 않습니다. 만료되면 다시 QR 로그인하세요."),
        "退出失败" to Translation("Sign-out failed", "ログアウトに失敗しました", "로그아웃 실패"),
        "退出编辑将丢失尚未保存的账号配置。" to Translation("Leaving will discard unsaved account settings.", "終了すると未保存のアカウント設定は破棄されます。", "나가면 저장하지 않은 계정 설정이 사라집니다."),
        "选择后立即生效；跟随系统会随系统深色模式自动切换。" to Translation("Changes apply immediately. Follow system tracks the system dark mode.", "選択はすぐに反映されます。「システム設定」は端末のダークモードに従います。", "선택 즉시 적용됩니다. 시스템 설정은 기기의 다크 모드를 따릅니다."),
        "邮件重新发送失败，请稍后重试" to Translation("Failed to queue the email again. Try later.", "メールの再送信登録に失敗しました。しばらくしてから再試行してください。", "이메일 재전송 등록에 실패했습니다. 잠시 후 다시 시도하세요."),
        "部分国产 ROM 还需在系统设置中允许「自启动 / 后台运行」权限。" to Translation("Some Android ROMs also require auto-start or background activity permission in system settings.", "一部の端末では、システム設定で自動起動またはバックグラウンド動作の許可も必要です。", "일부 Android ROM에서는 시스템 설정에서 자동 시작 또는 백그라운드 실행 권한도 필요합니다."),
        "预支持游戏" to Translation("Preview games", "プレビュー対応ゲーム", "미리 지원되는 게임"),
        "📁 保存 JSON" to Translation("📁 Save JSON", "📁 JSONを保存", "📁 JSON 저장"),
        "📁 保存 TXT" to Translation("📁 Save TXT", "📁 TXTを保存", "📁 TXT 저장"),
        "📁 保存 ZIP" to Translation("📁 Save ZIP", "📁 ZIPを保存", "📁 ZIP 저장"),
        "📤 分享 JSON" to Translation("📤 Share JSON", "📤 JSONを共有", "📤 JSON 공유"),
        "📤 分享 TXT" to Translation("📤 Share TXT", "📤 TXTを共有", "📤 TXT 공유"),
        "📤 分享 ZIP" to Translation("📤 Share ZIP", "📤 ZIPを共有", "📤 ZIP 공유"),
        "签到奖励：水晶、吃货啾啾、超钻石喵王等" to Translation("Check-in rewards: Crystals and more", "ログインボーナス：水晶など", "출석 보상: 수정 등"),
        "签到奖励：水晶、星石、体力药水等" to Translation("Check-in rewards: Crystals, Asterite, Stamina Potions, and more", "ログインボーナス：水晶、星石、体力の薬など", "출석 보상: 수정, 성석, 체력 물약 등"),
        "签到奖励：未名晶片、未名币、法理之谕等" to Translation("Check-in rewards: S-Chips, Stellin, Oracle of Justice, and more", "ログインボーナス：ステラウェハ、ステラコイン、法理の論など", "출석 보상: 미림 칩, 미림 달러, 법리의 훈계 등"),
        "签到奖励：原石、摩拉、冒险家的经验等" to Translation("Check-in rewards: Primogems, Mora, Adventurer's Experience, and more", "ログインボーナス：原石、モラ、冒険家の経験など", "출석 보상: 원석, 모라, 모험가의 경험 등"),
        "签到奖励：星琼、信用点、冒险记录等" to Translation("Check-in rewards: Stellar Jade, Credits, Adventure Logs, and more", "ログインボーナス：星玉、信用ポイント、冒険記録など", "출석 보상: 성옥, 신용 포인트, 모험 기록 등"),
        "签到奖励：菲林、丁尼、正式调查员记录等" to Translation("Check-in rewards: Polychromes, Dennies, Senior Investigator Logs, and more", "ログインボーナス：ポリクローム、ディニー、正式調査員の記録など", "출석 보상: 폴리크롬, 데니, 정식 조사원 기록 등"),
        "预支持 · 签到参数待确认" to Translation("Preview support · Sign-in parameters pending", "プレビュー対応・受取パラメーター確認中", "미리 지원 · 출석 매개변수 확인 중"),
        "领取云游戏免费时长" to Translation("Claim free cloud gaming time", "クラウドゲームの無料プレイ時間を受け取る", "클라우드 게임 무료 시간 받기"),
        "待机" to Translation("Idle", "待機", "대기"),
        "准备任务" to Translation("Preparing tasks", "タスクを準備中", "작업 준비 중"),
        "检查环境" to Translation("Checking environment", "環境を確認中", "환경 검사 중"),
        "刷新凭证" to Translation("Refreshing credentials", "認証情報を更新中", "인증 정보 갱신 중"),
        "执行签到" to Translation("Running sign-in", "受取を実行中", "출석 실행 중"),
        "保存结果" to Translation("Saving results", "結果を保存中", "결과 저장 중"),
        "发送通知" to Translation("Sending notifications", "通知を送信中", "알림 전송 중"),
        "异常结束" to Translation("Ended with an error", "異常終了", "오류로 종료됨"),
        "等待中" to Translation("Waiting", "待機中", "대기 중"),
        "结果待确认" to Translation("Result unconfirmed", "結果要確認", "결과 확인 필요"),
        "OPPO / realme / 一加：设置 → 应用管理 → 应用列表 → 找到本应用 → 耗电保护 → 允许后台运行" to Translation("OPPO / realme / OnePlus: Settings → App management → App list → This app → Battery protection → Allow background activity", "OPPO / realme / OnePlus：設定 → アプリ管理 → アプリ一覧 → 本アプリ → バッテリー保護 → バックグラウンド動作を許可", "OPPO / realme / OnePlus: 설정 → 앱 관리 → 앱 목록 → 이 앱 → 배터리 보호 → 백그라운드 실행 허용"),
        "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 找到本应用 → 允许后台高耗电" to Translation("vivo / iQOO: Settings → Battery → Background power management → This app → Allow high background power use", "vivo / iQOO：設定 → バッテリー → バックグラウンド消費電力管理 → 本アプリ → 高消費電力を許可", "vivo / iQOO: 설정 → 배터리 → 백그라운드 전력 관리 → 이 앱 → 높은 백그라운드 전력 사용 허용"),
        "三星：设置 → 电池和设备维护 → 电池 → 后台使用限制 → 将本应用移至「不受限」" to Translation("Samsung: Settings → Battery and device care → Battery → Background usage limits → Add this app to Never sleeping apps", "Samsung：設定 → バッテリーとデバイスケア → バッテリー → バックグラウンド使用を制限 → 本アプリを制限なしに設定", "Samsung: 설정 → 배터리 및 디바이스 케어 → 배터리 → 백그라운드 사용 제한 → 이 앱을 제한 없음으로 설정"),
        "小米 / Redmi：设置 → 应用设置 → 应用管理 → 找到本应用 → 自启动（开启）" to Translation("Xiaomi / Redmi: Settings → Apps → Manage apps → This app → Autostart (On)", "Xiaomi / Redmi：設定 → アプリ → アプリ管理 → 本アプリ → 自動起動（オン）", "Xiaomi / Redmi: 설정 → 앱 → 앱 관리 → 이 앱 → 자동 시작(켜기)"),
        "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」→ 手动打开全部三项开关" to Translation("Huawei / HONOR: Settings → Apps → App launch → This app → Disable Manage automatically → Enable all three options", "Huawei / HONOR：設定 → アプリ → アプリ起動 → 本アプリ → 自動管理を無効 → 3項目をすべて有効", "Huawei / HONOR: 설정 → 앱 → 앱 실행 → 이 앱 → 자동 관리 끄기 → 세 항목 모두 켜기"),
        "⚠️ 会同时处理多个账号，短时间请求增多，可能导致账号风控，账号较多时请谨慎开启。" to Translation("⚠️ Multiple accounts run together, increasing short-term requests and risk-control exposure. Use with care.", "⚠️ 複数のアカウントを同時処理するため、短時間のリクエストが増えます。慎重に使用してください。", "⚠️ 여러 계정을 동시에 처리하여 단시간 요청과 보안 제한 위험이 증가합니다. 신중하게 사용하세요."),
        "魅族：设置 → 应用管理 → 找到本应用 → 权限管理 → 后台管理 → 允许后台运行" to Translation("Meizu: Settings → App management → This app → Permissions → Background management → Allow background activity", "Meizu：設定 → アプリ管理 → 本アプリ → 権限管理 → バックグラウンド管理 → バックグラウンド動作を許可", "Meizu: 설정 → 앱 관리 → 이 앱 → 권한 관리 → 백그라운드 관리 → 백그라운드 실행 허용"),
    )

fun localizeText(
    text: String,
    language: AppLanguage,
): String {
    if (language == AppLanguage.SIMPLIFIED_CHINESE || language == AppLanguage.SYSTEM) return text
    val exact = exactTranslations[text] ?: additionalExactTranslations[text]
    if (language == AppLanguage.TRADITIONAL_CHINESE) {
        if (exact != null) return toTraditionalChinese(text)
        localizeTraditionalDynamicText(text)?.let { return it }
        // 未知内容可能是用户自定义名称或服务端原文，不能擅自转换。
        return text
    }
    exact?.let { return it.forLanguage(language) }
    localizeDynamicText(text, language)?.let { return it }

    // 未知字符串可能包含用户自定义账号名或服务端原文，禁止做分词式猜测翻译。
    // 只有上方精确文案和明确动态模板才会本地化，无法安全识别的内容保持原样。
    return text
}

private fun localizeTraditionalDynamicText(text: String): String? {
    Regex("^确定删除「(.+)」吗？该操作不可恢复。$").matchEntire(text)?.let { match ->
        return "確定刪除「${match.groupValues[1]}」嗎？此操作無法復原。"
    }
    Regex("^「(.+)」的本地凭证无法解密或格式已损坏。(.+)$").matchEntire(text)?.let { match ->
        return "「${match.groupValues[1]}」的本機憑證無法解密或格式已損壞。" +
            toTraditionalChinese(match.groupValues[2])
    }
    Regex("^(.+) · (原神|崩坏：星穹铁道|崩坏3|崩坏学园2|未定事件簿|绝区零|云原神|云崩铁)$")
        .matchEntire(text)?.let { match ->
            return "${match.groupValues[1]} · ${toTraditionalChinese(match.groupValues[2])}"
        }
    return if (localizeDynamicText(text, AppLanguage.ENGLISH) != null) toTraditionalChinese(text) else null
}

private fun localizeDynamicText(
    text: String,
    language: AppLanguage,
): String? {
    fun t(en: String, ja: String, ko: String) = Translation(en, ja, ko).forLanguage(language)
    Regex("^(\\d+) 项任务$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count tasks", "$count 件のタスク", "${count}개 작업")
    }
    Regex("^(\\d+) 条日志$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count log entries", "$count 件のログ", "${count}개 로그")
    }
    Regex("^累计签到 (\\d+) 天$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count total sign-in days", "累計 $count 日受取", "누적 출석 ${count}일")
    }
    Regex("^本月 (\\d+) 天$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count days this month", "今月 $count 日", "이번 달 ${count}일")
    }
    Regex("^连续 (\\d+) 天$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count-day streak", "$count 日連続", "${count}일 연속")
    }
    Regex("^版本 (.+)$").matchEntire(text)?.let { match ->
        val version = match.groupValues[1]
        return t("Version $version", "バージョン $version", "버전 $version")
    }
    Regex("^发现新版本 (.+)$").matchEntire(text)?.let { match ->
        val version = match.groupValues[1]
        return t("New version $version available", "新しいバージョン $version があります", "새 버전 $version 사용 가능")
    }
    Regex("^正在下载更新 (.+)$").matchEntire(text)?.let { match ->
        val version = match.groupValues[1]
        return t("Downloading update $version", "アップデート $version をダウンロード中", "업데이트 $version 다운로드 중")
    }
    Regex("^每天 (\\d{2}:\\d{2})$").matchEntire(text)?.let { match ->
        val time = match.groupValues[1]
        return t("Daily at $time", "毎日 $time", "매일 $time")
    }
    Regex("^已签(\\d+)$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("Already claimed $count", "受取済み $count", "이미 수령 $count")
    }
    Regex("^待确认(\\d+)$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("Unconfirmed $count", "要確認 $count", "확인 필요 $count")
    }
    Regex("^已启用 (\\d+)/(\\d+)$").matchEntire(text)?.let { match ->
        val enabled = match.groupValues[1]
        val total = match.groupValues[2]
        return t("Enabled $enabled/$total", "有効 $enabled/$total", "사용 중 $enabled/$total")
    }
    Regex("^已完成 (\\d+) / (\\d+)$").matchEntire(text)?.let { match ->
        val completed = match.groupValues[1]
        val total = match.groupValues[2]
        return t("Completed $completed / $total", "完了 $completed / $total", "완료 $completed / $total")
    }
    Regex("^还有 (\\d+) 条，前往「记录」查看全部$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count more — open Records to view all", "残り $count 件・履歴ですべて表示", "${count}개 더 있음 · 기록에서 모두 보기")
    }
    Regex("^可用 (\\d+) 个，需处理 (\\d+) 个$").matchEntire(text)?.let { match ->
        val available = match.groupValues[1]
        val issues = match.groupValues[2]
        return t("$available available, $issues need attention", "$available 件利用可能・$issues 件要対応", "${available}개 사용 가능, ${issues}개 처리 필요")
    }
    Regex("^(.+) · 已启用 (\\d+)/(\\d+) 个账号 · (\\d+) 款游戏$").matchEntire(text)?.let { match ->
        val date = match.groupValues[1]
        val enabled = match.groupValues[2]
        val total = match.groupValues[3]
        val games = match.groupValues[4]
        return t(
            "$date · $enabled/$total accounts enabled · $games games",
            "$date ・有効 $enabled/$total アカウント・$games ゲーム",
            "$date · 계정 $enabled/$total 사용 중 · 게임 ${games}개",
        )
    }
    Regex("^(\\d+) 款游戏可配置$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count configurable games", "$count ゲームを設定可能", "게임 ${count}개 설정 가능")
    }
    Regex("^共 (\\d+) 条日志$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count log entries", "ログ $count 件", "로그 ${count}개")
    }
    Regex("^(\\d+)/(\\d+) 条日志$").matchEntire(text)?.let { match ->
        val shown = match.groupValues[1]
        val total = match.groupValues[2]
        return t("$shown/$total log entries", "ログ $shown/$total 件", "로그 $shown/${total}개")
    }
    Regex("^共 (\\d+) 条记录$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("$count records", "履歴 $count 件", "기록 ${count}개")
    }
    Regex("^全部 (\\d+)$").matchEntire(text)?.let { match ->
        val count = match.groupValues[1]
        return t("All $count", "すべて $count", "전체 $count")
    }
    Regex("^(签到进行中|运行日志|错误日志|其它日志) · (.+)$").matchEntire(text)?.let { match ->
        val kind = localizeText(match.groupValues[1], language)
        val time = match.groupValues[2]
        return "$kind · $time"
    }
    Regex("^当前阶段：(.+)$").matchEntire(text)?.let { match ->
        val phase = localizeText(match.groupValues[1], language)
        return t("Current stage: $phase", "現在の段階：$phase", "현재 단계: $phase")
    }
    Regex("^当前：(.+)$").matchEntire(text)?.let { match ->
        val value = match.groupValues[1]
        return t("Current: $value", "現在：$value", "현재: $value")
    }
    Regex("^自定义 (.+)$").matchEntire(text)?.let { match ->
        val value = match.groupValues[1]
        return t("Custom $value", "カスタム $value", "사용자 지정 $value")
    }
    Regex("^默认 (.+)$").matchEntire(text)?.let { match ->
        val value = match.groupValues[1]
        return t("Default $value", "デフォルト $value", "기본값 $value")
    }
    Regex("^已下载 (.+)$").matchEntire(text)?.let { match ->
        val value = match.groupValues[1]
        return t("Downloaded $value", "ダウンロード済み $value", "다운로드됨 $value")
    }
    Regex("^(.+)扫码登录$").matchEntire(text)?.let { match ->
        val game = localizeText(match.groupValues[1], language)
        return t("$game QR code login", "$game QRコードログイン", "$game QR 코드 로그인")
    }
    Regex("^(.+) · (原神|崩坏：星穹铁道|崩坏3|崩坏学园2|未定事件簿|绝区零|云原神|云崩铁)$")
        .matchEntire(text)?.let { match ->
            val account = match.groupValues[1]
            val game = localizeText(match.groupValues[2], language)
            return "$account · $game"
        }
    Regex("^确定删除「(.+)」吗？该操作不可恢复。$").matchEntire(text)?.let { match ->
        val label = match.groupValues[1]
        return t(
            "Delete “$label”? This cannot be undone.",
            "「$label」を削除しますか？この操作は元に戻せません。",
            "‘$label’을(를) 삭제할까요? 이 작업은 되돌릴 수 없습니다.",
        )
    }
    Regex("^「(.+)」的本地凭证无法解密或格式已损坏。(.+)$").matchEntire(text)?.let { match ->
        val label = match.groupValues[1]
        val tail = localizeText(match.groupValues[2], language)
        return t(
            "Local credentials for “$label” cannot be decrypted or are corrupted. $tail",
            "「$label」のローカル認証情報を復号できないか、形式が破損しています。$tail",
            "‘$label’의 로컬 인증 정보를 복호화할 수 없거나 형식이 손상되었습니다. $tail",
        )
    }
    Regex("^（等待第(\\d+)次尝试）$").matchEntire(text)?.let { match ->
        val attempt = match.groupValues[1]
        return t(" (waiting for attempt $attempt)", "（$attempt 回目の試行待ち）", "(${attempt}번째 시도 대기 중)")
    }
    Regex("^邮件待发送(.*)$").matchEntire(text)?.let { match ->
        val suffix = match.groupValues[1].let { localizeDynamicText(it, language) ?: it }
        return t("Email pending$suffix", "メール送信待ち$suffix", "이메일 대기 중$suffix")
    }
    Regex("^(保存失败|发送失败|获取失败|检查更新失败|打开安装器失败|下载更新失败|签到失败|清空失败)[:：]\\s*(.+)$")
        .matchEntire(text)?.let { match ->
            val action = localizeText(match.groupValues[1], language)
            val detail = match.groupValues[2]
            return "$action: $detail"
        }
    return null
}

private fun Translation.forLanguage(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH -> en
        AppLanguage.JAPANESE -> ja
        AppLanguage.KOREAN -> ko
        else -> en
    }

private val traditionalPhrases =
    mapOf(
        "QuestTick" to "QuestTick",
        "签到" to "簽到",
        "签名" to "簽名",
        "崩坏：星穹铁道" to "崩壞：星穹鐵道",
        "崩坏学园2" to "崩壞學園2",
        "崩坏3" to "崩壞3",
        "绝区零" to "絕區零",
        "云原神" to "雲原神",
        "云崩铁" to "雲崩鐵",
        "未定事件簿" to "未定事件簿",
        "二维码" to "QR Code",
        "Cookie" to "Cookie",
        "Token" to "Token",
    )

private val traditionalCharacters =
    mapOf(
        '与' to '與',
        '专' to '專',
        '业' to '業',
        '东' to '東',
        '丢' to '丟',
        '两' to '兩',
        '严' to '嚴',
        '个' to '個',
        '为' to '為',
        '举' to '舉',
        '义' to '義',
        '习' to '習',
        '书' to '書',
        '争' to '爭',
        '于' to '於',
        '云' to '雲',
        '产' to '產',
        '仅' to '僅',
        '从' to '從',
        '仓' to '倉',
        '价' to '價',
        '优' to '優',
        '会' to '會',
        '传' to '傳',
        '伤' to '傷',
        '伪' to '偽',
        '体' to '體',
        '余' to '餘',
        '侧' to '側',
        '储' to '儲',
        '兑' to '兌',
        '关' to '關',
        '内' to '內',
        '册' to '冊',
        '写' to '寫',
        '军' to '軍',
        '冲' to '衝',
        '决' to '決',
        '况' to '況',
        '冻' to '凍',
        '准' to '準',
        '减' to '減',
        '凭' to '憑',
        '击' to '擊',
        '划' to '劃',
        '则' to '則',
        '刚' to '剛',
        '创' to '創',
        '删' to '刪',
        '别' to '別',
        '办' to '辦',
        '务' to '務',
        '动' to '動',
        '励' to '勵',
        '区' to '區',
        '华' to '華',
        '协' to '協',
        '单' to '單',
        '占' to '佔',
        '厂' to '廠',
        '历' to '歷',
        '压' to '壓',
        '参' to '參',
        '发' to '發',
        '变' to '變',
        '叠' to '疊',
        '台' to '臺',
        '号' to '號',
        '后' to '後',
        '吗' to '嗎',
        '听' to '聽',
        '启' to '啟',
        '员' to '員',
        '响' to '響',
        '园' to '園',
        '围' to '圍',
        '国' to '國',
        '图' to '圖',
        '圆' to '圓',
        '场' to '場',
        '坏' to '壞',
        '块' to '塊',
        '墙' to '牆',
        '声' to '聲',
        '处' to '處',
        '备' to '備',
        '复' to '復',
        '够' to '夠',
        '头' to '頭',
        '奖' to '獎',
        '学' to '學',
        '实' to '實',
        '审' to '審',
        '宽' to '寬',
        '对' to '對',
        '导' to '導',
        '将' to '將',
        '尝' to '嘗',
        '尽' to '盡',
        '层' to '層',
        '属' to '屬',
        '币' to '幣',
        '带' to '帶',
        '帧' to '幀',
        '幂' to '冪',
        '并' to '並',
        '广' to '廣',
        '库' to '庫',
        '应' to '應',
        '废' to '廢',
        '开' to '開',
        '异' to '異',
        '弃' to '棄',
        '弹' to '彈',
        '强' to '強',
        '归' to '歸',
        '当' to '當',
        '录' to '錄',
        '征' to '徵',
        '径' to '徑',
        '态' to '態',
        '总' to '總',
        '戏' to '戲',
        '户' to '戶',
        '托' to '託',
        '执' to '執',
        '扩' to '擴',
        '扫' to '掃',
        '抛' to '拋',
        '抢' to '搶',
        '护' to '護',
        '报' to '報',
        '担' to '擔',
        '拟' to '擬',
        '拦' to '攔',
        '择' to '擇',
        '挂' to '掛',
        '损' to '損',
        '换' to '換',
        '据' to '據',
        '携' to '攜',
        '敛' to '斂',
        '数' to '數',
        '断' to '斷',
        '无' to '無',
        '旧' to '舊',
        '时' to '時',
        '昵' to '暱',
        '显' to '顯',
        '暂' to '暫',
        '术' to '術',
        '机' to '機',
        '杀' to '殺',
        '权' to '權',
        '条' to '條',
        '来' to '來',
        '极' to '極',
        '构' to '構',
        '标' to '標',
        '栈' to '棧',
        '栏' to '欄',
        '样' to '樣',
        '档' to '檔',
        '检' to '檢',
        '残' to '殘',
        '毕' to '畢',
        '污' to '汙',
        '没' to '沒',
        '泄' to '洩',
        '洁' to '潔',
        '浅' to '淺',
        '测' to '測',
        '游' to '遊',
        '溃' to '潰',
        '滚' to '滾',
        '满' to '滿',
        '滤' to '濾',
        '灵' to '靈',
        '灾' to '災',
        '点' to '點',
        '烁' to '爍',
        '热' to '熱',
        '状' to '狀',
        '独' to '獨',
        '环' to '環',
        '现' to '現',
        '琼' to '瓊',
        '电' to '電',
        '画' to '畫',
        '监' to '監',
        '盖' to '蓋',
        '盘' to '盤',
        '码' to '碼',
        '础' to '礎',
        '确' to '確',
        '离' to '離',
        '种' to '種',
        '积' to '積',
        '称' to '稱',
        '稳' to '穩',
        '竞' to '競',
        '筛' to '篩',
        '签' to '籤',
        '简' to '簡',
        '类' to '類',
        '约' to '約',
        '级' to '級',
        '纯' to '純',
        '纳' to '納',
        '纹' to '紋',
        '线' to '線',
        '组' to '組',
        '细' to '細',
        '终' to '終',
        '经' to '經',
        '绑' to '綁',
        '结' to '結',
        '绘' to '繪',
        '给' to '給',
        '络' to '絡',
        '绝' to '絕',
        '统' to '統',
        '继' to '繼',
        '绪' to '緒',
        '续' to '續',
        '维' to '維',
        '综' to '綜',
        '缀' to '綴',
        '缓' to '緩',
        '编' to '編',
        '缘' to '緣',
        '缩' to '縮',
        '网' to '網',
        '职' to '職',
        '联' to '聯',
        '胀' to '脹',
        '胜' to '勝',
        '脑' to '腦',
        '脚' to '腳',
        '脱' to '脫',
        '节' to '節',
        '苹' to '蘋',
        '范' to '範',
        '荐' to '薦',
        '荣' to '榮',
        '药' to '藥',
        '获' to '獲',
        '蓝' to '藍',
        '补' to '補',
        '装' to '裝',
        '见' to '見',
        '观' to '觀',
        '规' to '規',
        '视' to '視',
        '览' to '覽',
        '触' to '觸',
        '计' to '計',
        '认' to '認',
        '让' to '讓',
        '议' to '議',
        '记' to '記',
        '许' to '許',
        '论' to '論',
        '设' to '設',
        '访' to '訪',
        '证' to '證',
        '评' to '評',
        '识' to '識',
        '诊' to '診',
        '词' to '詞',
        '译' to '譯',
        '试' to '試',
        '话' to '話',
        '询' to '詢',
        '该' to '該',
        '详' to '詳',
        '语' to '語',
        '误' to '誤',
        '说' to '說',
        '请' to '請',
        '读' to '讀',
        '调' to '調',
        '谕' to '諭',
        '谨' to '謹',
        '负' to '負',
        '责' to '責',
        '败' to '敗',
        '账' to '賬',
        '货' to '貨',
        '贴' to '貼',
        '费' to '費',
        '资' to '資',
        '赖' to '賴',
        '轨' to '軌',
        '转' to '轉',
        '轮' to '輪',
        '软' to '軟',
        '轻' to '輕',
        '载' to '載',
        '较' to '較',
        '辅' to '輔',
        '辑' to '輯',
        '输' to '輸',
        '边' to '邊',
        '达' to '達',
        '迁' to '遷',
        '过' to '過',
        '运' to '運',
        '还' to '還',
        '这' to '這',
        '进' to '進',
        '远' to '遠',
        '连' to '連',
        '迟' to '遲',
        '迹' to '跡',
        '适' to '適',
        '选' to '選',
        '递' to '遞',
        '逻' to '邏',
        '遗' to '遺',
        '邮' to '郵',
        '采' to '採',
        '释' to '釋',
        '里' to '裡',
        '鉴' to '鑑',
        '钟' to '鍾',
        '钥' to '鑰',
        '钮' to '鈕',
        '钱' to '錢',
        '钻' to '鑽',
        '铁' to '鐵',
        '链' to '鏈',
        '销' to '銷',
        '锁' to '鎖',
        '错' to '錯',
        '键' to '鍵',
        '镜' to '鏡',
        '长' to '長',
        '门' to '門',
        '闪' to '閃',
        '闭' to '閉',
        '问' to '問',
        '间' to '間',
        '阅' to '閱',
        '阈' to '閾',
        '队' to '隊',
        '阴' to '陰',
        '阶' to '階',
        '际' to '際',
        '陆' to '陸',
        '险' to '險',
        '随' to '隨',
        '隐' to '隱',
        '静' to '靜',
        '韩' to '韓',
        '页' to '頁',
        '顶' to '頂',
        '项' to '項',
        '顺' to '順',
        '须' to '須',
        '顾' to '顧',
        '预' to '預',
        '领' to '領',
        '频' to '頻',
        '题' to '題',
        '颜' to '顏',
        '额' to '額',
        '风' to '風',
        '饰' to '飾',
        '馈' to '饋',
        '验' to '驗',
        '齐' to '齊'
    )

private fun toTraditionalChinese(text: String): String {
    var result = text
    traditionalPhrases.entries.sortedByDescending { it.key.length }.forEach { (source, target) ->
        result = result.replace(source, target)
    }
    return buildString(result.length) {
        result.forEach { append(traditionalCharacters[it] ?: it) }
    }
}
