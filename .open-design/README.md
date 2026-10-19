# .open-design 工作区

交互原型的本地工作区。**完整维护说明见 [docs/UI设计/原型维护.md](../docs/UI设计/原型维护.md)。**

## 两条命令

```bash
node scripts/assemble-prototype.mjs           # src/ 分片 → 08-prototype.html
odops preview .open-design/08-prototype.html  # 浏览器预览，改完自动刷新
```

## 记住三件事

1. `08-prototype.html` 是**产物**，自动生成，别手改；
2. 改界面去 `src/screens/<屏>.html`，改全局样式去 `src/styles/`，改骨架去 `src/00-shell.html`；
3. 改完必须重新组装，CI 会跑 `--check` 拦「只改分片忘组装」。
