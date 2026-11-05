/** 站点线性图标路径；不含商品资料、统计或演示状态。 */
export const iconPaths:Record<string,string> = {
	note: "M6 3h9l4 4v14H6z M14 3v5h5 M9 12h7 M9 16h5",
	image: "M4 4h16v16H4z M4 16l5-5 4 4 3-3 4 4 M15 8h.01",
	palette: "M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1-3.7c-.9-.5-.7-1.8.4-1.8H17a4 4 0 0 0 4-4c0-5-4-8.5-9-8.5 M7 10h.01 M10 6.5h.01 M15 7h.01 M17.5 11h.01",
	clipboard: "M9 5H6v16h13V5h-3 M9 3h6v4H9z M9 12h7 M9 16h5",
	crop: "M7 3v14h14 M3 7h14v14 M3 3l18 18",
	folder: "M3 6h7l2 3h9v11H3z M3 6V4h7l2 3h7v2",
	qr: "M3 3h6v6H3z M15 3h6v6h-6z M3 15h6v6H3z M15 15h2v2h-2z M20 14v4h-3 M14 20h3 M20 21h1",
	convert: "M4 7h16l-4-4 M20 17H4l4 4 M20 7v4 M4 17v-4",
	clock: "M12 3a9 9 0 1 0 .01 0 M12 7v6l4 2",
	lock: "M7 10V7a5 5 0 0 1 10 0v3 M5 10h14v11H5z M12 14v3",
	shapes: "M12 3l5 8H7z M3 16h6v6H3z M18 15a3.5 3.5 0 1 0 .01 0",
	calendar: "M4 5h16v16H4z M8 3v4 M16 3v4 M4 10h16 M8 14h1 M13 14h3 M8 17h3",
	browser: "M3 4h18v16H3z M3 9h18 M6 6.5h.01 M9 6.5h.01 M7 16l3-3 3 3 3-4 3 5",
	record: "M3 5h18v14H3z M12 10a3 3 0 1 0 .01 0",
	ruler: "M4 4h16v16H4z M8 4v4 M12 4v2 M16 4v4 M4 8h3 M4 12h5 M4 16h3",
	chart: "M4 3v18h17 M8 17v-6 M13 17V7 M18 17V4",
	search: "M10.8 4a6.8 6.8 0 1 0 .01 0 M16 16l4.5 4.5",
	star: "m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z",
	fire: "M13 2c1 5-5 6-3 10 2-1 3-3 3-4 4 3 6 6 5 10-1 3-4 4-7 4-5 0-8-4-7-8 1-4 5-5 6-10 0 3 1 4 1 4 2-2 2-4 2-6z",
	download: "M12 3v12m-5-5 5 5 5-5 M4 15v5h16v-5",
	arrow: "M5 12h14m-5-5 5 5-5 5",
	close: "m6 6 12 12 M18 6 6 18",
	new: "M12 3v5 M12 16v5 M3 12h5 M16 12h5 M5.6 5.6l3.1 3.1 M15.3 15.3l3.1 3.1 M5.6 18.4l3.1-3.1 M15.3 8.7l3.1-3.1",
	eye: "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12 M12 9a3 3 0 1 0 .01 0",
	check: "m5 12 4 4 10-10",
	wallet: "M3 5h17v15H3z M3 5V3h15v2 M15 10h6v5h-6z M17.5 12.5h.01",
	back: "M19 12H5m5-5-5 5 5 5",
	zoom: "M10 4a6 6 0 1 0 .01 0 M15 15l6 6 M7 10h6 M10 7v6",
	chevron: "m9 5 7 7-7 7"
};
// 栏目使用语义名称，保持数据库已有名称兼容。
iconPaths.spark=iconPaths.new||iconPaths.shapes!;
iconPaths.flame=iconPaths.fire||iconPaths.shapes!;
