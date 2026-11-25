global using System;
global using System.Collections.Generic;

namespace Modern.CSharp10;

public class Greeter
{
    public string Greet(string name) => "Hello, " + name;

    public List<string> GreetAll(string[] names)
    {
        List<string> results = new();
        foreach (string n in names)
        {
            results.Add(Greet(n));
        }
        return results;
    }
}
