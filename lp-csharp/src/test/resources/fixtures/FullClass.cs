using System;

namespace Acme
{
    /// <summary>
    /// A fully-featured class.
    /// </summary>
    [Serializable]
    public class FullClass
    {
        /// <summary>A public counter field.</summary>
        public int Count;

        private string _name;

        /// <summary>The display name.</summary>
        public string Name
        {
            get { return _name; }
            set { _name = value; }
        }

        public event EventHandler Changed;

        public int this[int index]
        {
            get { return 0; }
            set { }
        }

        public FullClass()
        {
        }

        /// <summary>Adds two numbers.</summary>
        public int Add(int a, int b)
        {
            return a + b;
        }
    }
}
