class_name Widget
extends Control

enum Mode {
	RED,
	GREEN,
}

var active = true:
	set = set_active

func do_thing(
		a,
		b = 2):
	return a + b
