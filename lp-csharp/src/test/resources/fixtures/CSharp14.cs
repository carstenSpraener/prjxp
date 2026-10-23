using System;
using System.Collections.Generic;

Console.WriteLine("C# 14");

namespace Modern.CSharp14
{
    public union Shape
    {
        public double X;
        public double Y;
    }
}

// Extension block (C# 14)
extension IEnumerable<int>
{
    public static int SumAll(this IEnumerable<int> source)
    {
        int sum = 0;
        foreach (int i in source)
        {
            sum += i;
        }
        return sum;
    }
}
