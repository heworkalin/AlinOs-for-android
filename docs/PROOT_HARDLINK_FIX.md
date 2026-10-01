# proot 伪硬链接 chown/open ENOENT 修复记录

> 最后更新：2026-10-01

## 现象

容器内任何对「伪硬链接」的访问都报 `No such file or directory`：

```sh
touch /tmp/x && ln /tmp/x /tmp/y && chown 0:0 /tmp/y
# chown: changing ownership of '/tmp/y': No such file or directory
```

`apt install perl` / dpkg 解压因此失败：

```
dpkg: error processing archive ...: error setting ownership of
 '/usr/bin/perl5.38.2.dpkg-new': No such file or directory
```

`ls -li` 却显示 x/y inode 相同、`nlink=2`（这是 proot 伪装出来的，不是真硬链接）。

## 根因

proot 的 `--link2symlink`（`src/extension/link2symlink/link2symlink.c`）把硬链接变成：

```
/tmp/y  ->  <PROOT_L2S_DIR>/.l2s.y0001  ->  <PROOT_L2S_DIR>/.l2s.y0001.0002
             (intermediate, 符号链接)        (final, 真实数据文件)
```

`stat/lstat` 的结果由 `link2symlink` 的 `handle_sysexit_end()` 改写，伪装成硬链接。

但**访问路径时**，proot 会先做路径规范化（`src/path/canon.c` 的 `canonicalize()`）：

1. `y` 是符号链接 → `readlink(host_path)` 得到 **l2s 的宿主路径**；
2. `detranslate_path(scratch_path, t_referrer)`：referrer 属于 guestfs，不走 binding 分支；
   若 l2s 路径在 **rootfs 之外**，`compare_paths(get_root(), path)` 落到 `default:`，
   `sanity_check=false` → 原样返回，剥不掉前缀；
3. `canonicalize()` 把这个宿主路径**当 guest 路径**重新解析，`substitute_binding`
   给它拼上 rootfs → `<rootfs>/data/.../proot_l2s/...`；
4. `substitute_binding_stat()` 里 `lstat` 失败：

```c
if (!IS_FINAL(finality) && !S_ISDIR(statl.st_mode) && !S_ISLNK(statl.st_mode))
    return (status < 0 ? -ENOENT : -ENOTDIR);   /* ← 返回 -ENOENT */
```

5. `canonicalize()` 返回 `-ENOENT`，`translate_path()` 直接返回，
   **`TRANSLATED_PATH` 事件不触发** → `link2symlink` 的
   `translated_path()` / `resolve_faked_hard_link()` 根本没机会执行。

**结论：判定"文件不存在"的是 `canonicalize()`（path/canon.c），发生在 link2symlink 之前。**

## 修复

### 1. `PROOT_L2S_DIR` 必须位于 rootfs 内部

与 proot 官方测试一致（`tests/test-8d3c07f5.sh`）：

```sh
PROOT_L2S_DIR="${ROOTFS}/.l2s"  proot -l --rootfs="${ROOTFS}" ...
```

只有 backing file 在 rootfs 内，`detranslate_path()` 才能剥掉 rootfs 前缀，
`canonicalize()` 才能解析成功，后续 link2symlink 替换逻辑才会执行。

Java 侧统一为单一路径（`ProotContainerManager.l2sDir`）：

```java
public static File l2sDir(Context ctx) {
    return new File(containerDir(ctx), ".l2s");   // <rootfs>/.l2s
}
```

解压（`ProotContainerTestActivity`）、运行时 exec、运行时 PTY
（`LocalShellEnvironment`）三处共用。

> 切换 l2s 位置后，**必须重新解压 rootfs**：旧的伪硬链接目标字符串已经写死，
> 光改代码救不回来。

### 2. proot 源码补丁（本仓库外，构建源码在 Android-Proot-Builder 的 `/build/src/proot`）

**补丁 A：`src/extension/fake_id0/chown.c`** — meta 文件不存在时不再放行真 chown：

```diff
-	if(path_exists(meta_path) != 0)
-		return 0;
+	/* meta 不存在时继续处理：read_meta_file 给默认属主，write_meta_file 创建 meta，
+	 * 由 proot 接管 chown，避免落到真 syscall。 */
```

> 注意：该段在 `#ifdef USERLAND` 内。当前构建**没有定义 `USERLAND`**，
> 故此补丁默认不生效（二进制里是 `.l2s.` 前缀，而非 `.proot.l2s.`）。
> 保留以备将来切换 USERLAND 模式。

**补丁 B：`src/extension/link2symlink/link2symlink.c`** — `get_l2s_directory()` 解析软链接：

```diff
 static bool get_l2s_directory(void)
 {
 	const char *value;
 	size_t length;
+	char resolved[PATH_MAX];
 	...
 	value = getenv("PROOT_L2S_DIR");
 	if (value == NULL || value[0] == '\0')
 		return false;
+
+	/* 让 l2s_directory 与规范化后的路径一致（Android /data/user/0 是软链接） */
+	if (realpath(value, resolved) != NULL)
+		value = resolved;

 	length = strlen(value);
```

### 3. 关闭 f2fs workaround

`src/path/f2fs-bug.c` 的 `should_skip_file_access_due_to_f2fs_bug()` 在 Android `/data`
上容易误报，且用 `readdir` 判定文件存在与否，会漏掉刚创建的文件，
导致所有访问直接 `-ENOENT`。运行时设置：

```java
environment.put("PROOT_F2FS_WORKAROUND", "0");
```

### 4. 保证 rootfs 的 `/tmp` 存在

`/tmp` 缺失时，`canonicalize("/tmp/...")` 在 `tmp` 组件就返回 `-ENOENT`，
`translate` 日志没有 `->` 目标行。`ensureLoginScript()` 启动时 `mkdirs()` 兜底。

### 5. 环境变量传递

解压启动 proot 用 `ProcessBuilder.environment().put(...)` 叠加，
**不要用 `Runtime.exec(cmd, envp, dir)`**——`envp` 会替换整个环境，
proot 拿不到父进程环境时会退回默认（缓存）路径。

## 重新编译 proot

```bash
# 源码：/home/he/Android-Proot-Builder 的 Docker volume /build/src/proot
# NDK ：NDK29 的 linux-arm64 工具链（可在设备 aarch64 上直接运行）
export ANDROID_NDK_HOME=/home/he/Android/Sdk/ndk/29.0.14206865
# 每次切换架构前必须删掉旧的 /output 产物，否则脚本增量判断会跳过 proot 编译
rm -f /output/libproot.so /output/libproot-loader.so /output/libproot-loader32.so
TARGET_ARCH=aarch64 TALLOC_LINK=static bash build-android.sh
```

产物 → `app/src/main/jniLibs/<abi>/libproot.so`（loader 同理）。

## 验证

```sh
touch /tmp/x && ln /tmp/x /tmp/y && chown 0:0 /tmp/y; echo "exit=$?"   # exit=0
rm -f /tmp/x /tmp/y
apt install -y perl
```

## 补充：`ln` 普通符号链接导致 backing 名错乱

现象（`z -> y`，`y` 是伪硬链接）：

```sh
ln z c
ls -li
# z -> y0001      ← z 的目标被改写
# c -> y0001      ← 垃圾目标
```

**根因**：`move_and_symlink_path()` 在 `S_ISLNK(original)` 分支里，把
`readlink(original)` 的结果当作 intermediate。对「普通用户符号链接」，
链接目标（`y`）不是 PREFIX 开头，`first_link` 保持 1，随后

```c
sprintf(new_intermediate, "%s%04d", intermediate, 1);   // "y" + "0001" = "y0001"
```

就用链接目标名拼出了 backing，还 `l2s_rename()` 改写了原链接。

**修复**：非伪造硬链接（目标不以 PREFIX 开头）的符号链接，必须像普通文件一样
**用它自己的路径名**构造 intermediate。git diff 摘要：

```diff
 	if (S_ISLNK(statl.st_mode)) {
 		size = my_readlink(original, intermediate);
 		...
 		if (strncmp(name, PREFIX, strlen(PREFIX)) == 0)
 			first_link = 0;
-	} else {
-		/* compute new name */
+	}
+
+	if (first_link) {
+		/* compute new name from the original path */
 		name = strrchr(original,'/');
 		...
 		strcat(intermediate, PREFIX);
 		strcat(intermediate, name);
 	}
```
