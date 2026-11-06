/** 完整小清单的整数排序不转换Number，防止分/鼓励值大整数失真或按字符串字典排序。 */
export function compareIntegers(
  a: string | number,
  b: string | number,
): number {
  const left = BigInt(a),
    right = BigInt(b);
  return left < right ? -1 : left > right ? 1 : 0;
}
